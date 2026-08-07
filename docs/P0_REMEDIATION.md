# P0 Remediation — 2026-08-07

What changed in response to the P0 list in
[`PLATFORM_REVIEW.md`](PLATFORM_REVIEW.md) §14, how it was verified, and what is
deliberately still open.

Items 1–9, 12, 13 and 14 are addressed. Items 10 and 11 are scheduled as their own
gated builds — both are greenfield feature work, not defect fixes.

---

## Two things only you can do

Neither is a code change, and the code changes below do not substitute for them.

### 1. Rotate the leaked provider credentials

Four secrets were committed and are in git history. Removing them from the working
tree does not un-leak them — **rotation at the provider is the control that
matters.**

| Secret | Was at | Rotate at |
|---|---|---|
| NVIDIA NIM API key (`nvapi-…`) | `application.yml`, twice | NVIDIA NIM console |
| Meta WhatsApp access token (`EAA…`) | `application-local.yml` | Meta for Developers → your app |
| Meta WhatsApp **app-secret** | `application-local.yml` | Meta for Developers → your app |
| Dev AES / blind-index keys | `application.yml` | Regenerate if any real data was ever encrypted under them |

The app-secret is the most serious of the four and the review missed it: it is the
HMAC key that authenticates the inbound WhatsApp webhook, so anyone holding it can
forge delivery-status callbacks for any message.

### 2. Decide the third-party inference question

Enabling the assistant sends tool results — student names, attendance records,
grades — to a third-party inference endpoint. The assistant now ships off by
default, so nothing is flowing today. Before it is switched on anywhere real, that
needs a data-processing agreement or a provider you hold terms with.

---

## What changed

### 1. Secret literals removed; startup fails without a key — §1.1

- `application.yml`: `spring.ai.openai.api-key` → `${OPENAI_API_KEY:}`; the whole
  `schoolbridge.assistant` provider-key block deleted with the native gateways.
- `application-local.yml`: WhatsApp access token, phone-number-id, and app-secret
  are env-only. Absent them the app falls back to `LoggingWhatsAppClient` and sends
  nothing.
- `crypto.aes-key` / `blind-index-key` now have **no default** in the base config.
  A default there means any profile that forgot to override it silently encrypts
  real PII under a key that is public in this repo, and the failure is invisible
  until a breach. Throwaway values live in `application-local.yml` and
  `application-test.yml`; prod supplies them from the environment.
- New `AssistantStartupValidator` fails the context when the assistant is enabled
  without a configured chat provider or key — replacing the per-provider validation
  that lived in the deleted `*ClientConfig` classes.
- New `.env.example`, which `.gitignore` already referenced but did not exist.

### 2. Plaintext OTP logging deleted — §1.2

`ParentAuthService` logged the OTP at INFO in every profile. Deleted, along with the
Lombok `@Slf4j` that contradicted the project's no-Lombok convention.

`LoggingOtpDispatcher` still logs codes and is **intentionally kept**: it is fenced
to the `local` and `test` profiles so a developer can sign in without a WhatsApp
account. CI excludes it by name rather than by pattern, so the exemption is explicit.

### 3. Outbox relay is retry-safe and multi-instance-safe — §1.3

Migration `016-outbox-retry.sql` adds `next_attempt_at`, replaces
`idx_outbox_pending` with `idx_outbox_claimable (status, next_attempt_at)`, and adds
`idx_outbox_school`.

- `OutboxStatus` gains `DEAD`. `FAILED` is now **retryable**, not terminal.
- `markFailed` schedules exponential backoff (10s, 20s, 40s … capped at 30 min) and
  parks the row `DEAD` after 8 attempts, so a poison payload cannot spin the relay
  while its evidence stays for an operator.
- `claimDue` uses `PESSIMISTIC_WRITE` + a `-2` lock timeout, which Hibernate compiles
  to `FOR UPDATE SKIP LOCKED`. This is what makes running more than one pod safe:
  without it, two instances either double-publish every event or serialise behind
  each other.
- A `DEAD` row logs at ERROR (`outbox_dead`) — it means a parent will never receive
  that announcement, so it should page someone. `countByStatus` exists on the
  repository for querying it; there is **no** Micrometer gauge registered yet, so
  alert on the log line or scrape the count directly (`RUNBOOK.md` §5).

### 4. Multi-child acknowledgement — §1.4

`acknowledge` used `findFirst…`, so a parent with two children cleared one row and
stayed permanently unacknowledged for the sibling — with no UI affordance to fix it,
and a school ack report that under-counted. Now clears every row the parent holds
for that announcement.

### 5. Rate limiting — §1.5

- `RateLimitInterceptor` + `RateLimitWebConfig`: per-principal when authenticated
  (so a school behind one NAT gateway does not throttle itself), per-IP otherwise.
  120/min authenticated, 20/min anonymous, configurable.
- Deliberately a `HandlerInterceptor`, **not** a servlet `Filter`. An exception
  thrown from a filter escapes Spring MVC and renders as a container error page,
  losing the RFC-7807 body and the ar/en localisation every other error carries.
  The first implementation here was a filter; the integration test caught it.
- `OtpRequestRateLimiter`: 3/hour and 10/day per phone. Two windows because one is
  not enough — the hourly cap stops a burst, the daily cap stops a slow drip that
  would sit under it indefinitely. Keys are the blind-index hash, so Redis does not
  become a plaintext directory of parent phone numbers.
- The counter is only advanced for a **known** parent, after dispatch. Counting
  unknown numbers would let an attacker exhaust a real parent's budget by guessing,
  and would make a rate-limited response a phone-enumeration oracle.

**Known gap:** an unauthenticated flood against a *protected* endpoint is rejected
401 by the security filter chain before reaching any handler, so the interceptor
never counts it. Those 401s cost no database work, making this an edge/WAF concern
rather than an application one — but it is not full coverage. Public endpoints,
which are the ones doing real work while unauthenticated, are covered.

### 6. Assistant ships dark — §1.6

`enabled`, `actions.enabled`, and `rag.enabled` all default `false`, and
`spring.ai.model.chat` defaults to `none` so no provider client loads at all. The
comments claiming "ships dark" now match the values.

`AssistantDisabledByDefaultTest` boots the real defaults with no property overrides
and asserts the only gateway is `DisabledLlmGateway`, that no `ChatModel` bean
exists, and that no key is required. A comment cannot fail a build; this can.

### 7. Native LLM gateways deleted — §3.2

Seven files removed (Anthropic / Gemini / DeepSeek gateways and client configs, plus
`DeepSeekLlmGatewayTest`). `AssistantProperties` loses `engine`, `provider`,
`apiKey`, `geminiApiKey`, `deepseekApiKey`, and `deepseekBaseUrl`.

Deleted rather than flagged off because the flag defaulted the wrong way:
`AssistantProperties.engine` was initialised to `"native"` and every native bean
conditioned on `engine:native`, so only `application.yml` overriding it kept them
unloaded. `ASSISTANT_ENGINE=native`, or any config omitting the key, silently
swapped the entire inference path onto unmaintained code. This also removed the
second copy of the committed NVIDIA key.

Two gotchas were carried into ADR-007 before the file recording them was deleted:
Windows JDK 23 needs `SimpleClientHttpRequestFactory`, and `base-url` must not
include `/v1`.

### 8. CI — §4

`.github/workflows/ci.yml`. A **separate, first** job runs gitleaks plus two direct
greps — provider-key literals in config, and credential-shaped values in log
statements — so a leaked secret surfaces in seconds rather than after a full
Testcontainers build. Both greps were run locally against this tree and pass. The
build job runs `spotless:check` before `verify` so a formatting failure reports fast.

`liquibase:validate` was dropped from the workflow: there is no
`liquibase-maven-plugin` in the pom, so the step would have failed. The changelog is
exercised by the Testcontainers suite, which applies every changeset to a real
Postgres on each boot.

### 9. Scheduled announcements — §9

The review flagged this as unverified. It was **broken, and worse than described**:
`create()` recorded the dispatch outbox event unconditionally, so an announcement
scheduled for a future date went out within seconds while the UI showed SCHEDULED.

- `create()` now records the event only when the announcement is SENT.
- New `AnnouncementScheduleSweeper` releases due announcements every minute,
  re-binding the tenant per row and re-checking status inside the transaction so a
  recall between sweep and release still cancels, and a second sweep cannot
  double-send.
- Recipients stay materialised at create time, so the recipient set is the one that
  existed when the announcement was written. A student enrolled between scheduling
  and sending does not silently receive it. That is the conservative choice and
  worth revisiting if schools ask for the other behaviour.

The **WhatsApp webhook signature check was already correct** — HMAC-SHA256 over the
raw bytes, constant-time compare via `MessageDigest.isEqual`, verified before
parsing on every POST, and failing closed when the app-secret is blank. No change.

### 13. N+1 and fan-out — §11

- `list()` used one `countByAnnouncementId` per row. Now one grouped
  `countByAnnouncementIdIn` per page.
- Hibernate JDBC batching enabled (`batch_size: 50`, ordered inserts). Fan-out for a
  1,500-student school drops from ~2,500 insert round trips to ~50. There was no
  batching configured at all before; `@UuidGenerator` assigns ids pre-insert, so
  batching actually engages.
- **The set-based `INSERT … SELECT` the review suggested was rejected.** The scope
  queries are JPQL and rely on the Hibernate `@Filter` for tenant scoping. A native
  query bypasses that filter, so the "optimised" version would fan an announcement
  out to *every school's* parents unless the tenant predicate were hand-written
  correctly in all four scope variants. Moving fan-out to an async job returning 202
  is the right fix, is an API change, and belongs in P1 with the
  `tenant-isolation-auditor` run against it.

### 12. Row-Level Security on tenant tables — §4

Migration `017-tenant-rls.sql` puts `ENABLE ROW LEVEL SECURITY` plus a tenant policy
on all 20 tables backing a `TenantEntity`, generalising what changelog 014 did for
the vector store alone.

Until now isolation was entirely application-side: the Hibernate `@Filter` on
`TenantEntity`, activated by `TenantFilterAspect`. That aspect no-ops outside a
transaction, and `@Filter` never applies to `em.find()` — which is why every
repository hand-overrides `findById` with JPQL. Both are conventions; a native query
or a stray `EntityManager` call skips them silently. The policies move the last line
of defence into the database.

- `TenantSessionBinder` issues `set_config('app.current_tenant', …, true)`.
  Transaction-local, so it resets on commit and cannot leak onto the next borrower of
  a pooled connection. Called from `TenantFilterAspect` on the **same**
  once-per-transaction guard that enables the Hibernate filter — one condition, so
  the two controls can never disagree, at a cost of one round trip per transaction.
  `RagRetriever` and `DocumentIngestionService` were issuing this statement inline;
  both now reuse the helper.
- Policies are **not FORCEd**, matching 014: the table owner bypasses (keeping local
  dev and the whole Testcontainers suite unchanged) while they enforce for the
  least-privilege production role. `application-prod.yml` therefore splits
  `spring.liquibase.user` (owner, DDL) from `spring.datasource.username` (runtime,
  owns nothing) — `RUNBOOK.md` §1 has the `CREATE ROLE`/`GRANT` script.
- `RlsStartupValidator` runs `SELECT row_security_active('students')` under the `prod`
  profile and refuses to boot if false. Without it, deploying as the owner disables
  every policy and *nothing fails* — the worst possible failure mode for a security
  control. **This class has no automated test**: it is `@Profile("prod")`, so covering
  it would mean booting a prod context with every secret present, and a mocked
  version would only exercise the `if`, not the SQL that could actually be wrong.
  It is verified by the manual boot check in `RUNBOOK.md` §1 — worth running once per
  environment on first deploy.
- Excluded on purpose: `outbox_events` and `audit_logs` carry `school_id` but are
  infrastructure — `OutboxRelay.claimDue` must drain every school from one relay, so
  a policy there would need a bypass wrapping the entire relay, which is not a
  control. Neither has a user-facing read endpoint.

**Two corrections to the plan, found by building it:**

1. The plan named four `users` call sites needing a bypass. Only **two** do —
   `AuthServiceImpl.findByEmail` and `ParentAuthService.findAllByPhoneHash`, both of
   which run with `TenantContext` empty because they are resolving *who the caller
   is*. The two uniqueness checks in `UserServiceImpl.create` do not: the Hibernate
   filter already narrows them to the same tenant the GUC binds, so RLS changes
   nothing there. Bypassing anyway would have widened the exemption for no reason.
2. `UserServiceImpl.create` had a real problem the plan missed, in the other
   direction. It is SUPER_ADMIN-only, and a platform admin carries no school-scoped
   principal, so `TenantContext` is empty — meaning the `INSERT` would have been
   rejected by `WITH CHECK` and user creation would have broken outright in
   production. Fixed by wrapping the body in `TenantContext.runAs(schoolId, …)`,
   which is what `list()` and `findById()` in the same class already do.

**A defect the test caught before it shipped.** The policy was first written
`school_id = current_setting('app.current_tenant', true)::uuid`, on the assumption
that an unset GUC yields NULL. It does — once. After `set_config` has run on that
connection, a reset leaves an empty *string*, and `''::uuid` raises `invalid input
syntax for type uuid`. On a pooled connection the second unbound query of a session
would 500 rather than return nothing. Now `nullif(current_setting(…), '')::uuid`,
which genuinely fails closed. Written up as `COMMON_MISTAKES.md` §11.

### 14. README and runbook — §13

`README.md` was a 16-byte UTF-16 stub rendering as `# D2S`. Replaced with a real
UTF-8 README: stack, module map, quick start, build commands, the three profiles and
what each assumes, test strategy, and an index into `docs/`.

New `docs/RUNBOOK.md`: the two-role database setup RLS depends on, secret rotation
(including what rotating `AES_KEY` actually costs), health and metrics, all six
scheduled jobs with their properties, alarms, four incident playbooks, and an honest
known-gaps section.

---

## Verification

New tests, all passing:

| Test | Proves |
|---|---|
| `AnnouncementParentAckIntegrationTest.parentWithTwoChildren_singleAcknowledge_clearsEveryRecipientRow` | one tap clears both children's rows |
| `OutboxEventRetryTest` (5 cases) | failure is retryable, backoff widens, DEAD at the ceiling and unclaimable |
| `OutboxClaimIntegrationTest` (3 cases) | `FOR UPDATE SKIP LOCKED` compiles and runs against real Postgres; backoff and DEAD respected |
| `AssistantDisabledByDefaultTest` | real defaults load no LLM, no `ChatModel`, no key needed |
| `AnnouncementScheduleSweeperIntegrationTest` (4 cases) | future scheduling defers dispatch; due releases once; immediate path unaffected; a second sweep does not double-send |
| `RateLimitIntegrationTest` (2 cases) | cap returns **429** (not a 500 from an unhandled filter exception); actuator exempt |
| `OtpRequestRateLimitIntegrationTest` (2 cases) | **dispatch count** stops at the cap; unknown numbers never consume budget and stay indistinguishable |
| `TenantRlsIntegrationTest` (6 cases) | the database — not Hibernate — hides another tenant's row, admits the owning tenant's, fails closed when unbound, rejects a foreign `school_id` insert via `WITH CHECK`, and releases the bypass afterwards |
| `RlsTenantIsolationTest` (4 cases, rewritten) | the changelog-014 vector-store policy holds under a raw SQL query, so the assertion no longer depends on the retriever's metadata filter |

Both run `SET LOCAL ROLE` onto an unprivileged role rather than `FORCE`ing the
tables. Testcontainers connects as the bootstrap **superuser**, and superusers
bypass RLS unconditionally — FORCE only subjects the *owner*. The first version of
`TenantRlsIntegrationTest` FORCEd the tables, proved nothing, and would have passed
with the policies deleted. `RlsTenantIsolationTest` predates this work and had the
same flaw; it was converted to the role approach in the same pass, and the shared
setup now lives in the `RlsTestRole` test helper. Written up as
`COMMON_MISTAKES.md` §12.

Existing suites re-run green: all `Announcement*` (25), `ParentAuthIntegrationTest`,
`SpringAiEngineWiringTest`, `AssistantEnabledWiringTest`.

CI greps executed locally against this tree: no provider-key literals in
`src/**/resources`, no credential-shaped values in `log.*` statements.

---

## Not done

| Item | Why it is not a fix |
|---|---|
| **10 — File upload pipeline** | Greenfield. Presigned upload, MIME sniffing, AV scanning, per-tenant prefixing, presigned download, retention. `attachment_key` is an opaque string today and MinIO sits in `docker-compose.yml` with no client in the pom. |
| **11 — Notification preferences + per-user quiet hours** | Greenfield. Needs a `notification_preferences` table and push unified into `NotificationDispatcher`, which is currently a separate path from WhatsApp/SMS. |

Both are scheduled as their own gated builds rather than folded in here: each needs
its own migration, endpoints and tenant-isolation audit, and bundling them would have
produced one unreviewable diff. Object storage for item 10 will use the AWS SDK v2 S3
client — it drives the MinIO already in `docker-compose.yml` via an endpoint
override and real S3/R2 later without a code change, and presigned PUT/GET is the
whole point, since user files must never proxy through the API origin.

Also unchanged and still worth attention: the `role_permissions` table has no
`school_id`, so a runtime grant applies platform-wide (§4). The seed is careful, so
there is no live escalation — but the model is one bad grant away from one.
