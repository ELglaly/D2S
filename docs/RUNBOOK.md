# SchoolBridge Runbook

How to deploy and operate this service. Architecture lives in
[`ARCHITECTURE.md`](ARCHITECTURE.md); the *why* behind decisions is in
[`adr/`](adr/). This file is only what an operator needs at 3am.

---

## 1. Database roles

**Production requires two database identities.** This is not optional — it is the
only thing that makes row-level security do anything.

PostgreSQL lets a table's owner bypass RLS. The changelog-017 policies are
deliberately not `FORCE`d (so local development and the Testcontainers suite work
unchanged), which means an application connecting as the owner gets **no
database-level tenant isolation at all** — and gets it silently. Nothing errors; the
policies simply never evaluate.

```sql
-- As the database owner, once per environment.
CREATE ROLE schoolbridge_app LOGIN PASSWORD '<from your secret manager>' NOBYPASSRLS;

GRANT CONNECT ON DATABASE schoolbridge TO schoolbridge_app;
GRANT USAGE ON SCHEMA public TO schoolbridge_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO schoolbridge_app;

-- Liquibase creates new tables as the owner; without this, the app cannot read them
-- after the next migration and you find out in production.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO schoolbridge_app;
```

Then:

| Variable | Role |
|---|---|
| `DB_USERNAME` / `DB_PASSWORD` | `schoolbridge_app` — runtime. Owns nothing. |
| `DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD` | The owner. Used only by Liquibase at startup. |

`RlsStartupValidator` runs `SELECT row_security_active('students')` on boot under the
`prod` profile and **fails the context** if the answer is false. If startup dies with
*"Row-Level Security is not active"*, the app is connecting as the owner or a
superuser — fix the credentials, do not disable the check.

Verify by hand:

```sql
SET ROLE schoolbridge_app;
SELECT count(*) FROM students;                                    -- 0: fails closed
SELECT set_config('app.current_tenant', '<a-school-uuid>', false);
SELECT count(*) FROM students;                                    -- that school only
RESET ROLE;
```

### How the tenant reaches the database

`TenantFilterAspect` calls `TenantSessionBinder.bindTenant` on the first repository
call of each transaction, issuing `set_config('app.current_tenant', …, true)`. The
`true` makes it **transaction-local**, so it resets on commit and cannot leak onto
the next borrower of a pooled connection.

Two reads step outside it via `TenantSessionBinder.withBypass`: staff login by email
and the parent OTP lookup by phone hash. Both resolve *who the caller is* and so
cannot know a tenant yet. Those are the only two, by design — anything that knows its
school uses `TenantContext.runAs` instead.

---

## 2. Secrets

All secrets come from the environment. `.env.example` documents every variable.

`AES_KEY` and `BLIND_INDEX_KEY` have **no default anywhere** — the app will not start
without them, in any profile. That is intentional: a default means a
misconfigured environment silently encrypts real student PII under a key that is
public in this repository, and nothing surfaces until a breach.

### Rotation

| Secret | Where | Notes |
|---|---|---|
| `OPENAI_API_KEY` | Provider console | Assistant only; safe to rotate any time |
| `WHATSAPP_ACCESS_TOKEN` | Meta for Developers → your app | Sends stop until updated |
| `WHATSAPP_APP_SECRET` | Meta for Developers → your app | **This is the webhook HMAC key.** Anyone holding it can forge delivery-status callbacks for any message. Treat as a password. |
| `BLIND_INDEX_KEY` | Regenerate | Rotating it orphans every existing `phone_hash` — parents can no longer be found by phone. Needs a re-hash migration. |
| `AES_KEY` | Regenerate | **Rotating it makes every encrypted column unreadable.** Student names, announcement bodies, homework descriptions. Requires a planned re-encryption, not a config change. |
| `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` | Regenerate | Invalidates live access tokens; clients recover on refresh |

Anything committed to git stays leaked after it is removed from the tree. **Rotation
at the provider is the control that matters**, not history rewriting.

---

## 3. Health and metrics

Actuator exposes `health`, `info`, `prometheus` in `prod` (`metrics` too in other
profiles). Readiness includes `db`, so a pod goes unready when PostgreSQL is
unreachable.

```
GET /actuator/health/liveness
GET /actuator/health/readiness
GET /actuator/prometheus
```

Useful counters: `whatsapp.send.success`, `whatsapp.send.failure`,
`notification.fallback.sms`.

---

## 4. Scheduled jobs

All six run in-process. Every one is safe to run on multiple pods **except** where
noted.

| Job | Cadence | Property |
|---|---|---|
| `OutboxRelay.publishPending` | 5s | `schoolbridge.outbox.relay.poll-interval` (`enabled` must be `true` in prod) |
| `AnnouncementScheduleSweeper` | 1m | `schoolbridge.announcements.sweeper.release-rate` |
| `AttendanceSweeper` — release deferred alerts | 1m | `schoolbridge.attendance.sweeper.deferred-release-rate` |
| `AttendanceSweeper` — missed roster detection | `0 */15 * * * *` | `schoolbridge.attendance.sweeper.missed-roster-cron` |
| `HomeworkReminderSweeper` — fire reminders | `0 */5 * * * *` | `schoolbridge.homework.sweeper.fire-cron` |
| `HomeworkReminderSweeper` — release deferred | 1m | `schoolbridge.homework.sweeper.deferred-release-rate` |

The outbox relay claims rows with `FOR UPDATE SKIP LOCKED`, so running more than one
pod neither double-publishes nor serialises. Sweepers re-check status inside their
transaction before acting, so a concurrent recall still wins.

---

## 5. Alarms

| Signal | Meaning | Action |
|---|---|---|
| Log `outbox_dead` at ERROR | An event exhausted all 8 attempts. **A parent will never receive that announcement or absence alert.** | Page. Read `last_error`, fix the cause, then re-queue by setting `status='PENDING', attempts=0, next_attempt_at=now()`. |
| `SELECT count(*) FROM outbox_events WHERE status='DEAD'` > 0 | Same, as a query. There is no Micrometer gauge for this yet — alert on the log line or scrape this. | As above |
| Growing `status='PENDING'` with old `created_at` | Relay stopped or RabbitMQ is down | Check `schoolbridge.outbox.relay.enabled`, then the broker |
| `whatsapp.send.failure` rising, `notification.fallback.sms` rising | WhatsApp degraded; traffic is falling back to SMS at higher cost | Check Meta status and the WABA quality rating |
| Startup failure "Row-Level Security is not active" | App connected as the DB owner | §1 |
| Startup failure naming `spring.ai.openai.api-key` | Assistant enabled without a key | Set the key or `ASSISTANT_ENABLED=false` |

---

## 6. Playbooks

### Outbox backlog

1. `SELECT status, count(*) FROM outbox_events GROUP BY status;`
2. `DEAD` rows never retry — they need the manual re-queue above.
3. `FAILED` rows retry automatically with widening backoff (10s → 30m ceiling, 8
   attempts). A large `FAILED` count is usually the broker, not the payload.
4. Relay disabled? `schoolbridge.outbox.relay.enabled` is `false` in the base config
   and must be `true` in `prod`.

### WhatsApp circuit breaker open

The Resilience4j breaker (`whatsapp`) opens at a 50% failure rate over 20 calls and
half-opens after 30s. While open, `NotificationDispatcher` treats calls as failures
and falls back to SMS per recipient, tracked in Redis for 10 minutes.

Nothing is lost while it is open — but SMS costs more and carries no template
formatting. If a **template** was rejected by Meta rather than the API being down,
sends fail silently per template name; check the WABA template status in Meta
Business Manager.

### Rate-limit complaints

`RateLimitInterceptor`: 120/min authenticated (per principal), 20/min anonymous (per
IP). Tune with `RATE_LIMIT_AUTHENTICATED` / `RATE_LIMIT_ANONYMOUS`, or set
`RATE_LIMIT_ENABLED=false` to disable entirely.

A whole school behind one NAT gateway is *not* throttled as one caller — the limit is
per principal once authenticated, which is exactly why it is keyed that way.

OTP requests are capped separately at 3/hour and 10/day **per phone**
(`OTP_MAX_PER_HOUR`, `OTP_MAX_PER_DAY`), because each one costs a real send. A parent
locked out must wait; there is no manual reset endpoint.

### Suspected tenant leak

1. Confirm RLS is live: `SET ROLE schoolbridge_app; SELECT row_security_active('students');`
2. Confirm the policy: `SELECT * FROM pg_policies WHERE tablename = 'students';`
3. Every `TenantEntity` repository must override `findById` with JPQL — Hibernate's
   `@Filter` does not apply to `em.find()`. Run the `tenant-isolation-auditor` agent
   over the repositories.
4. Grep for `withBypass` — there should be exactly two production call sites
   (`AuthServiceImpl`, `ParentAuthService`).

---

## 7. Known gaps

Carried forward deliberately; see [`P0_REMEDIATION.md`](P0_REMEDIATION.md) and
[`PLATFORM_REVIEW.md`](PLATFORM_REVIEW.md).

- **Rate limiting does not cover requests rejected before the handler.** It is a
  `HandlerInterceptor`, so an unauthenticated flood against a *protected* endpoint is
  401'd by the Spring Security filter chain and never counted. Those 401s cost no
  database work, making this an edge/WAF concern — but it is not full coverage.
  Public endpoints, which are the ones doing real work while unauthenticated, are
  covered.
- **The assistant ships off and must stay off** until the third-party-inference data
  question is settled. Enabling it sends student names, attendance and grades to an
  external endpoint (ADR-007).
- **`role_permissions` has no `school_id`**, so a runtime permission grant applies to
  every school at once. The seed is careful, so there is no live escalation — but
  treat any grant as a platform-wide change.
- **No object storage.** `attachment_key` is an opaque string; there is no upload,
  scanning, or signed download. MinIO in `docker-compose.yml` is unused.
- **No per-parent notification preferences.** Quiet hours are school-wide and apply
  to attendance alerts only.
