# SchoolBridge — Platform Review

**Date:** 2026-08-07
**Reviewer:** architecture / security / product review pass
**Scope:** the whole backend at `E:\D2L` — architecture, features, database, API,
security, UX, notifications, scalability, AI, technical debt, release readiness.
**Baseline:** `main` @ `a455655`, with 69 uncommitted modified files in the working
tree (mostly `assistant/tools/**`).

Companion decision record: [ADR-007](adr/ADR-007-scope-correction-and-assistant-freeze.md).

> **Remediation status (2026-08-07, same day).** P0 items 1–9 and 13 are fixed;
> see [§14](#14-release-plan) for the per-item status and
> [`docs/P0_REMEDIATION.md`](P0_REMEDIATION.md) for what changed and how it was
> verified. Findings below are preserved as written at review time — they describe
> the state at `a455655`, not the current tree. Two corrections and one new defect
> found during remediation are marked inline.
>
> **Still outstanding and not startable by a code change alone:** rotating the
> committed provider keys at NVIDIA and Meta (§1.1), and the data-processing
> decision for sending student PII to third-party inference (§1.6).

---

## 0. Scope correction — read this first

This review was commissioned against a feature list describing messaging, bus
tracking with shareable parent-subscription links, a school calendar, file
attachments, admin and analytics dashboards, WebSocket real-time communication,
and notification preferences.

**Most of that is not implemented.** Reviewing it would have produced a fictional
report. What follows is grounded in the code that exists.

### Verified inventory

| Metric | Value |
|---|---|
| Modules | 12 — `tenant, identity, classes, subjects, grades, announcements, attendance, homework, integrations, assistant, common, config` |
| Controllers / endpoints | 25 / 99 |
| Main source | 428 files, ~26,000 LOC |
| DB tables | 28 across 15 Liquibase changelogs |
| Test classes | 84 |

Built and in reasonable shape: multi-tenancy (Hibernate `@Filter` + `TenantContext`
+ ArchUnit + per-repository cross-tenant tests), JWT staff auth and OTP parent
auth, RBAC (`@RequirePermission` + AOP over a DB-backed catalog), announcements
with fan-out and acknowledgement, attendance with alerting and quiet hours,
homework with a reminder sweeper, grades, subjects, WhatsApp/SMS/FCM integrations,
a transactional outbox, an audit log, AES-GCM field encryption with a blind index,
an idempotency filter, ar/en i18n, OpenAPI, Prometheus/OTel.

### Claimed but absent

| Claimed | Reality |
|---|---|
| Messaging | No module, table, or endpoint. `assistant/conversation/*` is AI-chat history, not teacher↔parent messaging. SRS FR-6 specifies it; unbuilt. |
| Bus tracking, shareable links, driver/supervisor roles | Entirely absent — no table, entity, endpoint, or role. `SchoolBridge_SRS_v1.0.md` §1.4 puts transport tracking out of v1 scope explicitly. |
| School calendar | Absent. |
| Real-time / WebSocket | Absent. No `spring-boot-starter-websocket`, no STOMP or SockJS anywhere. |
| File attachments | Absent as a feature. `attachment_key VARCHAR(512)` is an opaque string on `announcements` and `homework_items` with no upload, storage, scanning, or signing. MinIO is in `docker-compose.yml` but no S3/MinIO client is in `pom.xml`. `HomeworkItem.java` says so: "S3 wiring deferred". |
| Admin / analytics dashboard | Absent. No aggregate or report endpoints; SRS FR-7 unbuilt. |
| Notification preferences, digests, delivery tracking | Partial — WhatsApp→SMS fallback and per-recipient delivery rows exist. No preferences table, no digest, no per-user quiet hours. |
| Docker | `docker-compose.yml` + `Dockerfile` exist for local dev. No orchestration manifests, no CI. |

The gated build order in `.claude/CLAUDE.md` corroborates this: `homework ← current`,
with `fees → messaging → reporting → audit → hardening` not started. Those modules
are **unbuilt, not removed** — the payment-removal instruction in the brief maps
only onto `fees`, which was never begun.

**The platform is roughly 55–60% of the product described in the brief, and the
missing portion is the part that makes it "a private WhatsApp Community" rather
than a broadcast tool.**

---

## 1. Critical defects

Concrete bugs and exposures in committed code, not design opinions.

### 1.1 Live API key committed to source control — CRITICAL

`src/main/resources/application.yml` hardcodes an NVIDIA NIM key **twice** as a
default value — at `spring.ai.openai.api-key` and at
`schoolbridge.assistant.deepseek-api-key`, prefix `nvapi-…`. It is in git history.

- **Impact:** anyone with repo read access can bill the NIM account and replay prompts.
- **Fix:** rotate the key now; reduce both literals to `${OPENAI_API_KEY:}` /
  `${DEEPSEEK_API_KEY:}`; fail fast at startup when `assistant.enabled=true` and
  the key is blank; add gitleaks to CI. History purging is optional once the key
  is rotated — rotation is the control that matters.
- **Complexity:** S. **Risk of the fix:** none.

Related: `schoolbridge.crypto.aes-key` and `blind-index-key` carry working dev
defaults. `application-prod.yml` overrides both with required env vars so prod is
safe, but any non-prod profile silently encrypts real data under a publicly-known
key. Give both no default and fail startup when absent.

> **Correction — the exposure was larger than this.** Remediation found two more
> live secrets that this review missed, both in `application-local.yml`: a Meta
> WhatsApp **access token** and the WhatsApp **app-secret**. The app-secret is the
> HMAC key that authenticates the inbound webhook, so anyone holding it can forge
> delivery-status callbacks. All four literals are now removed, but **both provider
> keys still need rotating at NVIDIA and at Meta** — that is the control that
> matters and it cannot be done from the codebase.

### 1.2 Parent OTP logged in plaintext — CRITICAL

`src/main/java/com/schoolbridge/api/identity/auth/ParentAuthService.java:64`

```java
log.info("code is  {}", issued.code());
```

- **Impact:** full account takeover for every parent on the platform, from log
  access alone — and logs ship to a central store via the logstash encoder
  (`logback-spring.xml`). This is the single worst line in the codebase.
- **Fix:** delete the line. Add a CI check (regex or ArchUnit) that fails on
  logging of OTP/token/password/secret-shaped identifiers.
- Aside: this file uses Lombok `@Slf4j`, against the project's own no-Lombok
  convention in `.claude/CLAUDE.md`.

### 1.3 Outbox events are permanently lost on first failure — CRITICAL

`src/main/java/com/schoolbridge/api/common/outbox/OutboxRelay.java`

- `catch (RuntimeException) → event.markFailed(...)` is **terminal**. One RabbitMQ
  blip permanently drops an announcement or attendance alert — the exact payload
  the business promises to deliver.
  > **Correction:** this review also claimed the `attempts` column "is never
  > incremented". That was wrong — `OutboxEvent.markFailed` did increment it. The
  > defect is the terminal status and the absence of any retry, not the counter.
- The poll is `findByStatusOrderByCreatedAtAsc(PENDING, page)` with **no
  `FOR UPDATE SKIP LOCKED`**. Two app instances publish the same event twice. The
  relay is single-instance-only, which contradicts the platform's own scaling story.
- **Fix:** `SELECT … FOR UPDATE SKIP LOCKED`; increment `attempts`; exponential
  backoff via a `next_attempt_at` column; move to `DEAD` after N attempts; alert
  on the `DEAD` count; add a sweeper that retries `FAILED`.
- **Complexity:** M. **Priority:** ship-blocking.

### 1.4 Multi-child parents acknowledge only one child — HIGH

`AnnouncementServiceImpl.acknowledge` uses
`findFirstByAnnouncementIdAndParentUserId(...)`, but `announcement_recipients` is
keyed `(announcement_id, parent_user_id, student_id)` **precisely to support
per-child acknowledgement** (see the changeset comment in `006-announcements.sql`).
A parent with two children marks one row; the announcement stays unacknowledged
forever for the other child, and the school's ack report is wrong.

- **Fix:** acknowledge all rows for that parent, or accept `studentId` in the
  request. Check the parent-facing unacked query for the same shape.
- **Complexity:** S.

### 1.5 No rate limiting on 96 of 99 endpoints — HIGH

Only `POST /auth/login` (`LoginRateLimiter`) and the two assistant endpoints
(`AssistantRateLimiter`) are limited. `POST /api/v1/parents/auth/otp` is on the
`permitAll` list in `SecurityConfig` and is **unlimited** — an attacker can force
unbounded WhatsApp/SMS sends. That is direct monetary cost, WABA quality-rating
damage, and a plausible route to a number ban.

- **Fix:** a `RateLimitFilter` using Redis token buckets — per-IP on public paths,
  per-principal on authenticated paths, plus a hard per-phone cap on OTP request
  (e.g. 3/hour, 10/day). Reuse the existing `RateLimitException` and the
  `LoginRateLimiter` pattern rather than adding a library.
- **Complexity:** M.

### 1.6 Assistant defaults contradict their own documentation — HIGH

`application.yml` comments state the assistant "ships dark: enabled=false". The
actual defaults are `assistant.enabled=true`, `actions.enabled=true` (mutating
tools live), `rag.enabled=true`, `engine=springai`, `provider=deepseek`.

An LLM with write access to attendance, grades, homework, classes, students and
announcements is **on by default**, pointed at a third-party inference endpoint,
authenticating with the committed key from §1.1.

- **Fix:** flip all three defaults to `false`; correct the comments; require
  explicit per-environment opt-in. The `engine` key disappears entirely with the
  native-gateway deletion (§3.2) — Spring AI is the only engine, so the default
  cannot be wrong.
- **Also a compliance decision:** tool results carry student PII to NVIDIA-hosted
  inference. Confirm that is contractually acceptable for children's data before
  go-live, or route to a provider you hold terms with.
- **Complexity:** S for the config; the compliance call is yours.

---

## 2. Missing features, ranked

Priority answers "can you launch and retain a school without it".

| # | Feature | Business value | Priority | Complexity |
|---|---|---|---|---|
| 1 | **Teacher↔parent messaging** (SRS FR-6) | The product is pitched as a private WhatsApp community. Without 1:1 threads it is a broadcast tool, which schools already have for free via WhatsApp groups. This is the differentiator, and the reason a school switches. | Critical | L |
| 2 | **File / media pipeline** | `attachment_key` is a dead string. Parents expect a photo of the homework page and a PDF of the circular. Needs presigned upload, MIME/size validation, AV scanning, presigned time-limited download, per-tenant key prefixing, retention. | Critical | M |
| 3 | **Parent notification preferences + per-user quiet hours** | Quiet hours are school-wide only (`schools.alerts_respect_quiet_hours`). A 22:00 alert to a parent who opted out is the top uninstall and complaint driver, and in several jurisdictions a consent requirement. | Critical | M |
| 4 | **Admin dashboard + delivery/attendance reporting** (SRS FR-7) | Schools buy and renew on evidence that the message landed. Delivery rows exist; nothing aggregates them. | High | M |
| 5 | **School calendar / events** | Cheap, high perceived value, the natural home for exam dates and holidays, and it drives weekly re-opens. | High | S–M |
| 6 | **Full-text search** (announcements, homework, later messages) | Bodies are AES-GCM encrypted and `updatable=false` — search is currently *impossible by construction*. See §5.3. | High | M |
| 7 | **Bulk import / roster sync** | Onboarding a 1,200-student school by hand kills the sales cycle. `BulkImportIntegrationTest` exists — confirm how much of that path is real product vs. test scaffolding. | High | M |
| 8 | **Read receipts / delivery state surfaced to the sender** | The data partly exists (`message_id`, `delivery_status`); it is never exposed. | Medium | S |
| 9 | **Academic year / term model** | `school_classes.academic_year VARCHAR(20)` is free text. Year rollover, archival, and "last year's grades" all break without a first-class term entity. | Medium | M |
| 10 | **Bus / transport tracking** | Out of scope per the SRS, and correctly so — see §3.3. | Low (defer) | XL |

---

## 3. Cut, defer, or shrink

### 3.1 The AI assistant is over-built relative to the product — HIGH

`assistant/` is **149 files and 10,833 LOC — about 40% of all main source** — and
**31 of 84 test classes**. It contains two parallel LLM engines (hand-written
native gateways *and* Spring AI), three providers (Anthropic / Gemini / DeepSeek),
a 60+ tool registry with a confirmation and typed-confirm flow, a pending-action
store, a keyword tool-gating selector, a result projector, a RAG stack with
pgvector + RLS + chunker + a placeholder embedding model, per-tenant persona
settings, and a token-audit layer.

All of it was built while messaging, files, calendar, and reporting — the things
schools actually ask for — do not exist. This is the clearest misallocation in the
project.

- **Recommendation:** freeze it. Ship it disabled. **Spring AI is the only engine —
  the hand-written native gateways are deleted, not flagged off** (§3.2). Keep one
  primary provider plus one fallback. Keep read-only tools and **cut mutating tools
  from v1** — an LLM writing attendance and grades is a liability with no offsetting
  demand, and it doubles the authorization surface you must prove correct.
- **Value of cutting:** frees the single largest block of capacity for §2; removes
  the third-party PII path; removes the committed-key exposure class.
- **Risk:** sunk-cost resistance. Mitigate by keeping the *tool and RAG* layers
  behind flags; the native gateway code is removed outright and recoverable from git
  history if ever needed.

Recorded as a decision in [ADR-007](adr/ADR-007-scope-correction-and-assistant-freeze.md).

### 3.2 Delete the native LLM engine — Spring AI only — HIGH

**Decided:** there is one engine, and it is Spring AI. The hand-written native
gateway layer is removed from the tree, not left behind a flag.

Two engines × three providers is six wiring permutations today, each carrying
config, conditions, and tests (`SpringAiEngineWiringTest`,
`AssistantEnabledWiringTest`, `DeepSeekLlmGatewayTest`, …). Every one of those
permutations is a path someone can accidentally boot into — including
`engine=native`, whose gateways still default on: `AssistantProperties.engine`
is initialised to `"native"`, and every native bean carries
`@ConditionalOnExpression("… and '${schoolbridge.assistant.engine:native}'.equals('native')")`.
The only thing keeping them unloaded is `application.yml` overriding
`engine: ${ASSISTANT_ENGINE:springai}`. Setting `ASSISTANT_ENGINE=native`, or
running with a config that omits the key, silently swaps the entire inference
path.

**Delete** (7 files):

| File | Note |
|---|---|
| `assistant/llm/AnthropicClientConfig.java` | |
| `assistant/llm/AnthropicLlmGateway.java` | |
| `assistant/llm/GeminiClientConfig.java` | |
| `assistant/llm/GeminiLlmGateway.java` | |
| `assistant/llm/DeepSeekClientConfig.java` | |
| `assistant/llm/DeepSeekLlmGateway.java` | carries the Windows `SimpleClientHttpRequestFactory` and baseUrl-leading-slash gotchas — see below |
| `test/…/assistant/llm/DeepSeekLlmGatewayTest.java` | |

**Keep:** `LlmGateway` (the interface — it is the seam that makes this deletion
cheap), `DisabledLlmGateway`, the `Llm*` value types, `StreamText`,
`SystemPrompt`, and `llm/springai/SpringAiLlmGateway.java`.

**Also change:**

- `AssistantProperties` — drop the `engine` field and its accessors entirely. A
  property with one legal value is not a property. Drop `geminiApiKey`,
  `deepseekApiKey`, and `deepseekBaseUrl` too; provider credentials now live under
  `spring.ai.*` only, which removes the second home for the key in §1.1.
- `SpringAiLlmGateway` — its `@ConditionalOnExpression` loses the
  `engine.equals('springai')` clause and conditions on `assistant.enabled` alone.
- `application.yml` — remove `schoolbridge.assistant.engine`, the
  `provider`/`api-key`/`gemini-api-key`/`deepseek-api-key`/`deepseek-base-url`
  block, and every comment referencing `engine=native` (lines 15–19 and 30 of the
  `spring.ai` block still explain how to revert to it). Note this is also the edit
  that removes the second copy of the committed key (§1.1).
- `AssistantEnabledWiringTest` — assert no `engine` property is honoured and that
  `SpringAiLlmGateway` is the only `LlmGateway` when enabled.

**Two gotchas the deletion must not lose.** Both are recorded against the native
DeepSeek gateway and both apply to the Spring AI OpenAI client that replaces it:

1. On Windows JDK 23 the default JDK `HttpClient` request factory aborts;
   `SimpleClientHttpRequestFactory` is required. Verify how Spring AI's OpenAI
   client builds its factory before deleting the native one, or dev on Windows
   breaks with no obvious cause.
2. `base-url` must **not** include `/v1` — the Spring AI OpenAI client appends
   `/v1/chat/completions` itself, and a `.uri("/path")` call against a base with a
   path replaces rather than appends. `application.yml` already gets this right
   (`https://integrate.api.nvidia.com`, no `/v1`) while the native
   `deepseek-base-url` has `/v1`; do not copy the native value across.

**Complexity:** S–M. **Risk:** low — `LlmGateway` isolates callers, so nothing
outside `llm/` should need touching. **Value:** removes ~6 wiring permutations,
one of two key-bearing config surfaces, and three provider SDK paths.

### 3.3 Bus tracking: do not build it for v1

Live GPS is a different system — a mobile driver app, background location, battery
and permission handling, ingest at 1–5 s per vehicle, geofencing, ETA modelling,
map licensing, and an entire safety and privacy regime around **children's
real-time location**. It is XL, and it is the highest-liability feature in the
product. The SRS already excludes it.

If a launch customer demands something: ship the 10% that delivers 80% of the
value — the driver taps *departed / arriving in ~10 min / arrived*, parents get a
push. No live map, no continuous location storage. **Complexity:** S instead of XL,
and a far smaller privacy surface.

### 3.4 Smaller simplifications

- **`grades`** (9 files) — the SRS puts full grade books out of scope. Either
  commit to a real gradebook (weights, terms, report cards) or cut it; the current
  thin CRUD invites "where's the report card?" from every demo.
- **Idempotency filter** — correct and worth keeping, but it only helps if clients
  send the header. Document it in the client contract or it is dead weight.
- **Nine `docs/PLAN_*.md` assistant planning docs** — collapse into ADRs.
  `README.md` is a 16-byte stub while 21 docs exist, 9 of them about the assistant.

---

## 4. Architecture review

**Genuinely good — do not regress:** tenant isolation is real defence-in-depth
(Hibernate filter + AOP + the `findById` override rule + ArchUnit + per-repository
cross-tenant tests) and is better than most multi-tenant codebases; the outbox is
the right call for cross-module side effects; module boundaries are clean
package-by-feature; errors are centralised on RFC-7807; AES-GCM plus a blind index
for PII is above average for this market.

| Finding | Severity | Detail and fix |
|---|---|---|
| **Tenant filter engages only inside an active transaction** | High | `TenantFilterAspect` no-ops when `!isActualTransactionActive()`. Today `SimpleJpaRepository`'s own `@Transactional` covers you, but any future direct `EntityManager` use, native query, or `propagation=NOT_SUPPORTED` path silently returns cross-tenant rows. **Fix:** PostgreSQL RLS on every tenant table — you already do exactly this for `assistant_vector_store` in `014-assistant-vector-rls.sql`, so generalise it. The DB, not an aspect, should be the last line of defence. Highest security ROI in the codebase. Complexity M. |
| **`InheritableThreadLocal` tenant + virtual threads** | Medium | `TenantContext` uses `InheritableThreadLocal` while `spring.threads.virtual.enabled=true`. Any spawned thread or executor inherits the tenant, and `TenantBindingFilter` clears only when it bound. **Fix:** plain `ThreadLocal` with explicit propagation through `runAs`, or `ScopedValue`. |
| **Role→permission mapping is platform-global** | High | `role_permissions` (`015-authz.sql`) has no `school_id`. A runtime grant applies to **every school at once**. The seed is careful — `SCHOOL_MANAGE`, `MANAGE_ROLES`, `MANAGE_PERMISSIONS` go to `SUPER_ADMIN` only — so there is no live escalation, but the model is one bad grant away from cross-tenant compromise. **Fix:** add a nullable `school_id` (null = platform default) and resolve per tenant; and gate `SchoolController` on principal *kind* (platform admin) rather than on a mutable permission name, since its own OpenAPI annotations already claim "Requires SUPER_ADMIN" while the code enforces only `SCHOOL_MANAGE`. |
| **Authz cache is node-local Caffeine with a 10-minute TTL** | Medium | `AuthzCacheConfig` — a permission **revocation** stays live on other nodes for up to 10 minutes. The tradeoff is documented in the class javadoc, but it is a security window. **Fix:** Redis pub/sub invalidation with Caffeine as L1. |
| **Single role per user** | Medium | `users.role VARCHAR(20)`, one row per (person, school). A teacher who is also a parent at the same school cannot be modelled — a common real case. **Fix:** a `user_roles` join table; `PermissionAspect` already resolves permissions per role, so the aspect change is small. |
| **`users.email` is globally unique across tenants** | Medium | Deliberate (staff log in without knowing their `schoolId`), but one person cannot be staff at two schools, and signup collisions leak tenant membership. **Fix:** unique on `(school_id, email)` plus a school selector at login, or an email→tenant directory. |
| **No API versioning strategy** | Medium | `/api/v1` is a path prefix with no deprecation policy, no `Sunset` headers, no v2 story. With a Flutter client in the field you will need one inside a year. |
| **Two routing styles** | Low | `/api/v1/**` for domain, bare `/integrations/whatsapp/**` outside it. Unify. |
| **No CI pipeline** | High | No workflow files anywhere. `mvn -B -ntp verify` is the stated gate and nothing enforces it. Add GitHub Actions: `spotless:check`, SpotBugs, tests, Liquibase dry-run, gitleaks, dependency scan. Complexity S, value large. |
| **Under-engineered for the stated scale** | High | Single deployable, synchronous fan-out, poll-based relay, local caches, no read replica, no partitioning, no archival. Fine for 10 schools. See §11. |

---

## 5. Database review

### 5.1 Missing indexes

| Table | Add | Why |
|---|---|---|
| `announcement_recipients` | `(school_id, parent_user_id, created_at DESC)` | The parent inbox paginates by recency; the existing `(school_id, parent_user_id)` index forces a sort over every row that parent has ever received. |
| `attendance_alert_recipients` | `(school_id, parent_user_id, created_at DESC)` | Same access pattern. |
| `outbox_events` | `(school_id)`, and extend `(status, created_at)` for the retry sweeper | Per-tenant ops queries currently seq-scan. |
| `refresh_tokens` | `(expires_at)` | No index — any cleanup job scans the table. |
| `audit_logs` | `(school_id, entity_type, entity_id)` and `(school_id, actor_user_id, created_at DESC)` | Only `(school_id, created_at DESC)` exists; "what happened to this student" and "what did this admin do" are both unanswerable at speed. |
| `students` | GIN on a searchable name projection | See 5.3 — name search is impossible today. |

### 5.2 Missing tables for v1

`conversations`, `conversation_participants`, `messages`, `message_attachments`,
`message_reads` · `files` (key, tenant, owner, mime, size, checksum, scan status) ·
`notification_preferences` (user × category × channel, opt-out, quiet hours) ·
`calendar_events` · `academic_terms` · `user_roles` · `notification_deliveries`
(one unified table across channels — today delivery state is duplicated per feature).

### 5.3 Encryption blocks every read path you are about to need — HIGH

`students.full_name`, `announcements.body`, `homework_items.description`, and
`attendance_records.parent_response` are AES-GCM encrypted application-side via
`AesGcmAttributeConverter`. Live consequences:

- No `LIKE`/`ILIKE` or full-text search on any of them.
- No `ORDER BY name`.
- No DB-side reporting on content.
- `announcements.body` is additionally `updatable=false` — announcements can never
  be edited, only recalled.

**Fix:** keep the encryption, add a searchable projection — a normalised tokenised
blind index, a separately-keyed search column, or an index outside Postgres.
**Decide this before messaging ships.** Message search is table stakes, and
retrofitting it over an encrypted `messages.body` is far more expensive than
designing for it now. **Complexity:** M–L. **Priority:** High.

### 5.4 Fan-out write amplification

`announcement_recipients` and `attendance_alert_recipients` hold one row per
(parent, student, event). A school-wide announcement in a 1,500-student school is
roughly 2,500 rows; three per week is ~400k rows/year for one school. At 500
schools that is ~200M rows, with **no partitioning and no retention policy**. The
same applies to `audit_logs`, `outbox_events`, and `assistant_messages`.

**Fix:** monthly range partitioning on `created_at` for all five, plus a documented
retention and archival policy. Note the SRS mandates a legal retention period for
conversation logs — pick the number now, before messaging adds a sixth table.
**Complexity:** M.

### 5.5 Other schema issues

- `announcements.scope_value VARCHAR(100)` holds either a grade-level string *or* a
  stringified `class_id` UUID depending on `scope_type` — a polymorphic column with
  no FK and no referential integrity. Split into
  `scope_class_id UUID REFERENCES school_classes(id)` + `scope_grade_level`.
- `school_classes.academic_year VARCHAR(20)` free text → FK to `academic_terms`.
- `attendance_records` has no correction history. Attendance is legally significant
  and does get corrected; add an append-only correction trail rather than mutating
  `status` in place.
- `audit_logs.actor_user_id` is nullable with no FK — intentional (actors can be
  deleted, and system actions have no actor), but undocumented. Write it down.
  Related: `AnnouncementServiceImpl.recall()` passes `null` as the actor, so recalls
  are unattributable.

---

## 6. API review

| Finding | Priority | Fix |
|---|---|---|
| **Pagination is inconsistent** — 9 of 25 controllers use `Pageable`/`PageResponse` | High | Every collection endpoint returns a page. Enforce with an ArchUnit or OpenAPI test asserting no controller method returns a bare `List<T>`. |
| **N+1 on announcement list** | High | `AnnouncementServiceImpl.list()` and `findById()` call `recipients.countByAnnouncementId(a.getId())` once per row. Use a single grouped count, or denormalise `recipient_count` onto `announcements` and maintain it at fan-out. Cheap, measurable win. |
| **No filtering or sorting contract** | Medium | Announcements filter only by `status` — no date range, sender, or scope. Homework and attendance are similar. Define one convention and apply it everywhere. |
| **Idempotency key ignores the request body** | Medium | `IdempotencyFilter.buildKey` hashes tenant + method + URI + key but **not the payload**. Same key with a different body silently replays the earlier response. Fingerprint the body and return 422 on mismatch. |
| **No cursor pagination** | Medium | Offset paging over the parent inbox degrades as recipient tables grow. Move inbox and feed endpoints to keyset pagination. |
| **No bulk endpoints** | Medium | Attendance is per-record over HTTP. `MarkAllPresentTool` exists only as an assistant tool, not as REST. Teachers need one call per roster. |
| **Validation errors carry no field detail** | Low | `server.error.include-message: never` plus RFC-7807 is right, but `ValidationException` should still return per-field details so clients can highlight the offending input. |
| **No `ETag`/conditional GET, no response compression** | Low | Cheap mobile-bandwidth wins for the Flutter client. |
| **CORS: `allowCredentials(true)` with `allowedHeaders("*")`** | Medium | Tighten headers to an explicit list. Auth is a bearer token rather than a cookie, so `allowCredentials` can likely be `false` — confirm against the client. |
| **Swagger correctly disabled in prod** | ✅ | `application-prod.yml` disables both `swagger-ui` and `api-docs`, and `SecurityConfig` conditionally drops those paths from the allow-list. Good, keep it. |

---

## 7. Security review (beyond §1)

| Finding | Severity | Detail |
|---|---|---|
| Parent session is an opaque Redis token, 24 h, no refresh, no device binding | High | A Redis flush logs out every parent simultaneously. No revocation path, no "sign out other devices". **Fix:** persist parent sessions (or treat Redis as durable with AOF + replication), bind to a device, add a refresh flow. |
| OTP: 6 digits, 5 attempts, 5-minute TTL, unsalted SHA-256 in Redis | Medium | The attempt cap is correct. Unsalted SHA-256 of a 6-digit code is trivially reversible, but anyone reading Redis already holds the ticket, so real impact is low. The material gap is the missing per-phone request cap (§1.5). |
| No lockout or anomaly detection on parent auth | Medium | Staff login has `LoginRateLimiter`; parent OTP has nothing. |
| No file upload path, therefore no AV scanning, MIME allow-list, or real size enforcement | High (when built) | `spring.servlet.multipart.max-file-size: 5MB` is the only control and nothing uses it. Build the pipeline with validation from day one: presigned PUT, server-side MIME sniffing, ClamAV, per-tenant key prefix, presigned time-limited GET. Never serve user files from the API origin. |
| ~~No RLS on tenant tables except the vector store~~ | ~~High~~ | **Fixed** — changelog 017 covers all 20 tenant tables. See §4 and `P0_REMEDIATION.md` item 12. |
| Audit coverage is partial and unenforced | Medium | `AuditService` is called from some services; nothing guarantees every mutating endpoint audits. **Fix:** drive auditing from the same AOP layer as `@RequirePermission` so coverage is structural rather than remembered. |
| Audit log is not tamper-evident | Medium | "Append-only" by convention only — nothing prevents `UPDATE`/`DELETE`. Add a hash chain, or revoke UPDATE/DELETE on `audit_logs` for the application DB role. |
| No explicit security headers, no in-app HTTPS enforcement | Low–Medium | Relies on Spring Security defaults and an assumed upstream TLS terminator. Add explicit HSTS/CSP/referrer-policy; `forward-headers-strategy: framework` is already set, so `requiresChannel` is straightforward. |
| WhatsApp webhook — verify | High | `app-secret` and `verify-token` are configured. Confirm `IntegrationsWhatsAppWebhookController` performs constant-time HMAC-SHA256 validation on **every** POST and bounds replay. It is a `permitAll` path, making it the most exposed endpoint on the platform. |
| No dependency or secret scanning | High | No dependency-check, no gitleaks, no Dependabot. Given §1.1, secret scanning goes in first. |
| Assistant sends PII to third-party inference | High | Student names, attendance, and grades flow to NVIDIA-hosted DeepSeek by default. Needs an explicit DPA/consent decision, a provider you hold terms with, or the §1.6 default flip. |

---

## 8. User experience review

There is no UI in this repository, so this evaluates API-shape ergonomics per role.

**Parent — the worst-served role.** Verified issues: the multi-child acknowledgement
bug (§1.4); no per-parent notification preferences or quiet hours; no consolidated
feed — a parent must call announcements, homework, and attendance separately and
merge them client-side; no read state; no way to reply to anything.
**Fix:** one paginated `GET /api/v1/parents/feed` returning typed entries across all
three domains. Target: one screen, one call, zero taps to answer "what do I need to
know about my kids today".

**Teacher.** Attendance is the daily ritual and is per-record over HTTP. Ship
`POST /api/v1/attendance/roster` that takes the whole class defaulted to present
with only exceptions listed — roughly 30 taps down to 2. Homework needs a
duplicate-from-last-week action. Teachers also cannot see whether anything they
sent was delivered.

**School administrator — the buyer.** No dashboard, no reports, no bulk-import
flow, no delivery visibility. The API currently gives them nothing to justify a
renewal conversation.

**Bus supervisor.** The role does not exist. See §3.3.

**Cross-cutting.** No notification deep-links — a push should open the exact item.
No localisation of *content*, only of system messages (`messages_ar.properties` and
`messages_en.properties`, 164 keys each; `messages.properties` has 166 lines, a
small parity drift worth a pass with the `i18n-parity-auditor` agent).

---

## 9. Notification review

**Current state.** `NotificationDispatcher` is WhatsApp-first with per-recipient
Redis failure counting falling back to SMS, wrapped in a Resilience4j circuit
breaker and retry, with Micrometer counters. FCM push exists but **is not part of
the dispatcher** — it is a separate, parallel path. Quiet hours are school-level
only, with deferred release handled by the attendance and homework sweepers.

| Improvement | Priority | Note |
|---|---|---|
| Fold push into `NotificationDispatcher` as a first-class channel with a per-user preference order | Critical | Today push and WhatsApp/SMS are unrelated systems; there is no single answer to "was this parent notified". |
| `notification_preferences` — user × category × channel, opt-out, per-user quiet hours | Critical | See §2.3. |
| Digest notifications (daily or weekly parent summary) | High | Directly reduces WhatsApp template spend — the largest variable cost in this product — and reduces opt-outs. |
| Unified `notification_deliveries` + consume Meta delivery-status webhooks back onto rows | High | `message_id` is stored; nothing consumes delivery or read callbacks. |
| Retry with backoff + DLQ | Critical | First failure is currently terminal (§1.3). |
| **Verify scheduled announcements actually send** | High | `announcements.scheduled_for` and the `SCHEDULED` status exist, but the only sweepers found are `HomeworkReminderSweeper` and `AttendanceSweeper`. If no sweeper releases `SCHEDULED` announcements, that feature silently does nothing. Check this before anything else in this section. |
| Template versioning + per-school fallback when a WABA template is rejected | Medium | Template names are plain config strings; a rejection breaks sends with no visible failure mode. |
| Per-school cost metering | Medium | You pay per WhatsApp template. Meter it per tenant or you cannot price the product. |

---

## 10. Messaging review

There is nothing to review — messaging does not exist. Design guidance for when it
is built, since it is the #1 gap:

- **Model:** `conversations` (school, type `TEACHER_PARENT`/`CLASS`/`BROADCAST`,
  optional subject student) → `conversation_participants` → `messages` →
  `message_reads`. Keyset-paginate on `(conversation_id, created_at, id)`.
- **v1 must have:** send/receive, per-message delivery and read state, attachments,
  reply-to (a parent message id), search, pagination. **Do not** ship reactions,
  threads, voice notes, or typing indicators in v1 — they look cheap and are
  expensive to get right, and no school buys on them.
- **Offline sync:** a monotonic `server_seq` per conversation plus
  `GET /conversations/{id}/messages?since_seq=` beats timestamp-based sync for a
  Flutter client. Decide it at schema time, not after.
- **Real-time:** there is no WebSocket layer. Start with FCM push plus
  pull-on-open — 10% of the work, and it matches the actual usage pattern
  (parents are not sitting in the app). Add WebSocket/SSE only when in-app dwell
  time justifies it; a stateful socket layer changes the scaling story (sticky
  sessions or a Redis-backed broker relay).
- **Encryption vs. search:** resolve §5.3 *before* creating the `messages` table.
- **Policy:** the SRS requires admin-retrievable conversation logs, quiet hours, and
  a parent report/block keyword. Build the moderation path in v1, not after the
  first incident.

---

## 11. Scalability review

Target: hundreds of schools, tens of thousands of teachers, hundreds of thousands
of parents, millions of messages. Ordered by when each will actually break.

| Bottleneck | Breaks at | Fix | Complexity |
|---|---|---|---|
| **Outbox relay is single-instance and lossy** | The moment you run two pods | `FOR UPDATE SKIP LOCKED`, attempts, backoff, DLQ (§1.3) | M |
| **Synchronous fan-out inside the request transaction** | ~2–3k recipients | `AnnouncementServiceImpl` loads *every* `ParentStudentLink` for the school into memory and `saveAll()`s inside the HTTP transaction. **Fix:** set-based `INSERT … SELECT`, or enqueue a fan-out job and return 202. | M |
| **N+1 recipient counts** | ~50 announcements per page | Grouped count or denormalised counter (§6) | S |
| **No partitioning or retention** | ~50–100M rows | Monthly partitions on the five high-churn tables (§5.4) | M |
| **Single Postgres, no read replica** | ~200 schools | Route reporting and feed reads to a replica; the tenant filter works unchanged | M |
| **Hikari pool of 20 + virtual threads enabled** | Under burst | Virtual threads make it trivially easy to have 10k threads contending for 20 connections. Size the pool deliberately, add a bounded queue and acquisition timeout, and load-test this specific interaction. | S to change, M to validate |
| **Node-local Caffeine authz cache** | Multi-node (already true) | Redis pub/sub invalidation (§4) | S |
| **Single RabbitMQ exchange set, no per-tenant isolation or priority** | Noisy neighbour — one 5,000-student school's announcement delays another school's absence alerts | Priority queues (alerts above announcements) plus per-tenant rate shaping | M |
| **No CDN or object store for media** | First real attachment traffic | Never proxy files through the API (§7) | M |
| **No load or soak testing** | Unknown — which is the problem | Gatling or k6 against the top five endpoints, run weekly in CI | M |

The multi-tenant model is shared-schema with a discriminator. That is the right
call at this scale — **do not** move to schema- or database-per-tenant. The
constraint that will bite is the largest single tenant, not the tenant count:
build a noisy-tenant throttle before signing a 10,000-student school.

---

## 12. AI opportunities

Ranked by value over effort, reusing the RAG and tool infrastructure already built
(after the §3.1 trim).

| Opportunity | Business value | Priority | Complexity |
|---|---|---|---|
| **Automatic ar↔en translation** of announcements, homework, and later messages | The highest real value in this market. Removes the language barrier in both directions; every message becomes bilingual. Bounded, low-risk, no PII beyond what the recipient already sees. | High | S |
| **Drafting assistance for teachers** ("say this politely, in Arabic, to 30 parents") | Saves the teacher's scarcest resource. A human always approves before send, so there is no autonomy risk. | High | S |
| **Attendance-risk flagging** (chronic absence patterns → nudge the admin) | A measurable outcome schools are audited on. This is SQL and a threshold — build it **without** an LLM first. | High | S |
| **Parent Q&A over school documents** — the RAG already built | Deflects repetitive "when does term start" calls. Read-only, small blast radius. Ship this one. | Medium | S (already built) |
| **Semantic search** across announcements, homework, messages | Blocked on §5.3. Solve encryption-vs-search first; semantic ranking is then incremental. | Medium | M |
| **Summarised weekly parent digest** | Pairs with §9's digest work; the LLM adds polish over a template. | Medium | S |
| **Mutating assistant tools (write attendance/grades)** | **Recommend cutting.** Highest risk, lowest demand, doubles the authorization surface you must prove correct. | — | — |

---

## 13. Technical debt

- **No CI.** The highest-leverage item in this section (§4).
- **`README.md` is a 16-byte stub** reading "# D2S", against 21 docs — nine of them
  assistant plans. No setup guide, no runbook, no on-call doc.
- **Lombok is on the classpath while the convention is "no Lombok"** — and
  `ParentAuthService` uses `@Slf4j`. Either drop the dependency or drop the rule;
  the current state guarantees drift.
- **Config comments that actively mislead** (§1.6) — the comments say "ships dark",
  the defaults say otherwise. A reviewer trusting the comment ships a live LLM with
  write access.
- **Test distribution is inverted.** 31 of 84 test classes cover the assistant.
  `subjects` has none; `grades` has only `GradesAuthorizationIntegrationTest`.
  JaCoCo is configured but ungated, against a stated 80% standard.
- **69 uncommitted modified files** across the whole assistant tool layer. Commit
  or revert before starting any of this work, or the baseline is unknowable.
- **Duplicated fan-out logic.** `announcement_recipients` and
  `attendance_alert_recipients` are near-identical shapes with parallel
  materialise / deliver / defer code. Extract one recipient fan-out abstraction
  before `homework_recipients` and `messages` make it four copies.
- **`docker-compose.yml` runs MinIO that nothing connects to** — dead infrastructure
  implying a capability the code does not have.
- **No `.env.example`**, despite `.gitignore` explicitly un-ignoring one.

---

## 14. Release plan

### Essential before launch (P0)

Status as of 2026-08-07. Detail and verification:
[`docs/P0_REMEDIATION.md`](P0_REMEDIATION.md).

| # | Item | § | Status |
|---|---|---|---|
| 1 | Remove every secret literal; add gitleaks to CI | 1.1 | **Done in code** — but **rotate the NVIDIA and Meta keys at the provider**; only you can do that |
| 2 | Delete the OTP logging line; CI check for secret logging | 1.2 | **Done** |
| 3 | Outbox: `SKIP LOCKED`, backoff, DEAD | 1.3 | **Done** |
| 4 | Fix multi-child acknowledgement | 1.4 | **Done** |
| 5 | Rate limiting everywhere + per-phone OTP cap | 1.5 | **Done** (one known gap, below) |
| 6 | Flip assistant / actions / RAG defaults to off | 1.6, 3.1 | **Done in code** — the **third-party-PII decision is still open** |
| 7 | Delete the native LLM gateways | 3.2 | **Done** |
| 8 | Stand up CI | 4 | **Done** — not yet observed passing on GitHub |
| 9 | Verify scheduled announcements + webhook signature | 9, 7 | **Done** — webhook was already correct; scheduling was **broken worse than reported** (below) |
| 10 | File upload pipeline | 2.2 | **Scheduled as its own gated build** — greenfield feature |
| 11 | Parent notification preferences + quiet hours | 2.3 | **Scheduled as its own gated build** — greenfield feature |
| 12 | RLS on tenant tables | 4 | **Done** — changelog 017 on all 20 tenant tables; **prod must connect as a non-owner role** (below) |
| 13 | N+1 count and in-transaction fan-out | 11 | **N+1 done; JDBC batching added. Async fan-out deferred** (below) |
| 14 | README and operational runbook | 13 | **Done** — real README plus `docs/RUNBOOK.md` |

Four things found or decided during remediation that change what this section said:

- **Item 9 was a live defect, not just an unverified one.** `create()` recorded the
  dispatch outbox event unconditionally, so an announcement scheduled for a future
  date was delivered within seconds while the UI displayed SCHEDULED. The schedule
  was decorative. Now deferred at create and released by
  `AnnouncementScheduleSweeper`.
- **Item 13 is partly deferred.** The N+1 is fixed and JDBC batching cuts fan-out
  from ~2,500 round trips to ~50 for a 1,500-student school. The set-based
  `INSERT … SELECT` this section suggested was **rejected**: a native query bypasses
  the Hibernate `@Filter`, so it would fan an announcement out across every tenant
  unless the tenant predicate were hand-written correctly every time. Moving fan-out
  to an async job returning 202 is the right fix and is an API change, so it belongs
  in P1 with the tenant-isolation auditor run against it.
- **Item 12 carries a deployment obligation, not just a migration.** The policies are
  un-FORCEd (matching changelog 014), so PostgreSQL lets the table owner bypass them
  entirely. Production must therefore connect as a least-privilege role while
  Liquibase runs as the owner — `spring.liquibase.user` is now separate from
  `spring.datasource.username`, and `RlsStartupValidator` refuses to boot the `prod`
  profile if RLS is not actually in force. Deploy this as one role and the whole
  control is silently inert. Setup script: `RUNBOOK.md` §1.
- **Item 5 has a known gap.** The blanket limiter is a `HandlerInterceptor`, so it
  only sees requests that reach a handler. An unauthenticated flood against a
  *protected* endpoint is rejected 401 by the security filter chain and never
  counted. Those 401s cost no database work, so this is an edge/WAF concern —
  but it is a gap, not full coverage. Public endpoints are fully covered.

### Important after launch (P1)

Teacher↔parent messaging (§10) · admin dashboard and delivery reporting (§2.4) ·
the search-over-encrypted-content decision and its implementation (§5.3) · unified
notification delivery and digests (§9) · partitioning and retention (§5.4) ·
pagination everywhere plus cursor paging on feeds (§6) · multi-role users (§4) ·
bulk import and roster sync (§2.7) · load testing (§11)

### Nice to have (P2)

Calendar and events · academic-term model · AI translation and drafting (§12) ·
read receipts · replies and reactions in messaging · read replica · minimal
driver-tap bus updates (§3.3) — full live GPS tracking deferred indefinitely

---
  

## Related documents

- [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — system overview, dependency direction, folder conventions
- [`docs/COMMON_MISTAKES.md`](COMMON_MISTAKES.md) — the project's known gotchas, expanded
- [`docs/CHECKLISTS.md`](CHECKLISTS.md) — Definition of Done, review and release checklists
- [`docs/adr/`](adr/) — decision records, including [ADR-002](adr/ADR-002-tenant-isolation.md) (tenant isolation), [ADR-003](adr/ADR-003-rbac-aop-permissions.md) (RBAC), [ADR-004](adr/ADR-004-spring-ai-pgvector-rag.md) (RAG), and [ADR-007](adr/ADR-007-scope-correction-and-assistant-freeze.md) (this review's decisions)
- [`SchoolBridge_SRS_v1.0.md`](../SchoolBridge_SRS_v1.0.md) — the original requirements, including the FR-6 messaging and FR-7 reporting specifications referenced throughout
