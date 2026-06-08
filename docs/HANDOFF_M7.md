
# SchoolBridge — Handoff to a Fresh Session (M7)

You are continuing a multi-session SchoolBridge backend build. **M1–M6 are complete and verified green** (100/100 tests pass: `mvn test`). This document is the **only thing you need to read before starting**, plus the files it points at.

---

## Authoritative state (read these first, in order)

1. **`~/.claude/projects/E--D2L/memory/MEMORY.md`** — auto-loaded. Pulls in:
   - `project_schoolbridge.md` — locked-in stack + decisions resolving SRS contradictions (Liquibase not Flyway, Spotless+SpotBugs, etc.)
   - `feedback_schoolbridge_workflow.md` — **strict module-by-module gated cadence the user expects** (mini-plan → confirm → code → mvn test green → next module)
   - `feedback_hibernate_filter_findbyid_bypass.md` — **every TenantEntity repo MUST override findById with a JPQL @Query** (Hibernate's @Filter doesn't apply to Session.find)
   - `feedback_springsecurity_uptoken_trap.md` — never call `setAuthenticated(true)` after the 3-arg `UsernamePasswordAuthenticationToken` constructor
   - `feedback_outbox_audit_mapof_npe.md` — **outbox + audit payloads always have nullable fields; build with `HashMap`, never `Map.of(...)`**

2. **`E:\D2L\docs\IMPLEMENTATION_PLAN.md`** — the master plan. Skim §10 module roadmap; the M7 slice is your scope. §1.3 locks RestClient + Resilience4j and §6 specifies the adapter ports.

3. **`E:\D2L\SchoolBridge_SRS_v1.0.md`** — requirements. §1.3 / §5.1 / §6 / §7 (WhatsApp send + webhook) and NFR-P2 (5-minute end-to-end alert SLA, even though the attendance path is M8) are M7-relevant.

4. **The code at `E:\D2L\src\main\java\com\schoolbridge\api\`** — actual structure. Especially:
   - `common/outbox/` — `OutboxPublisher` (interface, no impl yet), `OutboxRelay` (already wired, gated on `schoolbridge.outbox.relay.enabled=true`), `OutboxEventRecorder` (the producer M6 calls)
   - `announcements/` (M6 — fires `announcement.created` events with payload `{announcementId, schoolId, language, body, attachmentKey, recipientCount}` and `announcement.recalled`; M7 consumes these)
   - `identity/otp/LoggingOtpDispatcher` — `@ConditionalOnMissingBean(OtpDispatcher.class)`; M7's `WhatsAppOtpDispatcher` will replace it transparently
   - `common/security/SecurityConfig` — `/integrations/whatsapp/webhook` is already in `PUBLIC_PATHS`

---

## What's already shipped (M1–M6)

| Module | What's in it |
|---|---|
| M1 skeleton | Maven (Boot 3.3.5, Java 21), Liquibase, Docker/compose, JSON logging, i18n bundles, Testcontainers smoke test |
| M2 common infra | RFC 7807 advice + 8-exception hierarchy + i18n resolver, `BaseEntity` (UUIDv7) + `TenantEntity`, `TenantContext`, AES-256-GCM column converter + blind-index hasher, idempotency filter (Redis), outbox + audit (entities + `OutboxPublisher` port + `OutboxRelay` polling job, both relay-gated), `SecurityConfig` stateless skeleton, `PageResponse`, `RequestIdFilter` |
| M3 tenant | `School` aggregate (embedded `SchoolSettings` w/ jsonb fee offsets), 003-tenant.sql, super-admin `/api/v1/schools` CRUD, audit + outbox + domain events on every state change |
| M4 identity | `User extends TenantEntity` (encrypted name/phone + phone_hash blind index), `PlatformAdmin`, `RefreshToken`, JJWT RS256 (ephemeral dev keypair fallback), parent OTP flow (Redis-backed, `LoggingOtpDispatcher` placeholder), `LoginRateLimiter`, `BearerAuthenticationFilter` + `TenantBindingFilter`, `TenantFilterAspect`, `TenantEntityArchUnitTest` build-time guard, mandatory cross-tenant isolation suite for `User` |
| M5 classes/students | 5 TenantEntity aggregates (`SchoolClass`, `Student`, `Enrollment`, `TeacherAssignment`, `ParentStudentLink`), 005-classes-students.sql, `PermissionsHelper` bean (`@perms.teacherTeaches`, `@perms.parentLinkedTo`), CSV bulk import, per-aggregate slice + integration tests, 5 cross-tenant isolation suites |
| M6 announcements | 2 TenantEntity aggregates (`Announcement`, `AnnouncementRecipient`), 006-announcements.sql with 3 §3.2 indexes (incl. partial `WHERE acknowledged_at IS NULL`), CSV-style 6-endpoint controller (`POST /`, `GET /`, `GET /{id}`, `POST /{id}/recall`, `GET /{id}/recipients`, `POST /{id}/acknowledge`), per-(parent, student) recipient materialization for SCHOOL/GRADE/CLASS/CUSTOM scopes, `announcement.created` + `announcement.recalled` outbox events with **HashMap payloads** (Map.of NPE'd on nullable attachmentKey), 3 new `PermissionsHelper` helpers (`canSendAnnouncementToScope`, `parentReceivedAnnouncement`, `isAnnouncementSender`), 2 cross-tenant isolation suites, controller matrix + fanout + parent-ack integration tests |

---

## Your task: Module 7 — WhatsApp integration

Per `docs/IMPLEMENTATION_PLAN.md` §10 and §1.3 / §5.1 / §6 / §7. Independently demoable: an `announcement.created` outbox row produced by M6 results in real WhatsApp template sends; the M4 parent OTP flow stops using `LoggingOtpDispatcher` and now sends OTPs over WhatsApp; a Meta webhook POST updates `AnnouncementRecipient.deliveryStatus` and `messageId`.

**Adapter ports (hex boundary — interface in `integrations/whatsapp/`, fake + cloud impls):**
- `WhatsAppClient` — `send(templateName, recipientPhone, language, List<String> components) → MessageSendResult{messageId, accepted}` plus optionally `sendText(...)` for free-form (used by future messaging in M11). RestClient-based, Resilience4j circuit breaker + retry, JSON logging of every send (template, phone-masked, status, latency, traceId).
- `SmsClient` — minimal interface; **fake impl only in M7** (real provider deferred to M14). Used by `NotificationDispatcher` when WhatsApp circuit is open or 2 failures/10m for the same recipient.
- (`StorageClient`, `EmailClient` stay deferred — out of M7 scope.)

**Dispatcher (the orchestrator):**
- `NotificationDispatcher` — chooses channel WhatsApp→SMS fallback after 2 consecutive failures or 10 minutes of WhatsApp circuit open (per §6). Wrapped in Resilience4j. Records audit + bumps recipient delivery status to `SENT` (or `FAILED` if both channels fail).

**Outbox publisher (the producer→consumer seam):**
- `RabbitOutboxPublisher implements OutboxPublisher` — routes events to topic exchanges based on `eventType` prefix: `announcement.*` → `schoolbridge.announcements`, `otp.*` → `schoolbridge.otp`, etc. Enables `schoolbridge.outbox.relay.enabled=true` in prod profile.
- **Consumers** in `integrations/whatsapp/`:
  - `AnnouncementCreatedConsumer` — pulls `announcement.created`, loads recipients in batches, calls `NotificationDispatcher.dispatch(...)` per row, updates each recipient (`deliveryStatus=SENT/FAILED`, `messageId=...`).
  - `AnnouncementRecalledConsumer` — pulls `announcement.recalled`, marks each recipient `FAILED` if not yet delivered (or logs only; see open question).
  - `OtpSendConsumer` — separate path: M7 introduces a thin `OtpService` outbox write (`otp.send` event) replacing the in-process dispatcher hook, OR keeps the in-process dispatcher and just swaps `LoggingOtpDispatcher` → `WhatsAppOtpDispatcher` (see open question).

**OTP migration path (smallest change wins):**
- Replace `LoggingOtpDispatcher` with `WhatsAppOtpDispatcher` (M7). Same `OtpDispatcher` interface, `@Component` activates via `@ConditionalOnMissingBean` ordering already wired in M4. Keep `LoggingOtpDispatcher` available for the `local`/`test` profile via `@Profile`.

**Webhook endpoint (`/integrations/whatsapp/webhook`, not under `/api`):**
- `GET` — verification challenge per Meta Cloud API spec (echo `hub.challenge` if `hub.mode=subscribe` and `hub.verify_token` matches `WHATSAPP_VERIFY_TOKEN`).
- `POST` — receives delivery + read callbacks. **Constant-time HMAC-SHA256 verify** of the body against `X-Hub-Signature-256` using `WHATSAPP_APP_SECRET`. Reject unsigned/bad-signature with 401.
- On valid payload: parse `entry[].changes[].value.statuses[]` (Meta format), for each `id` match an `AnnouncementRecipient` by `messageId`, update `deliveryStatus` to `DELIVERED`/`READ`/`FAILED`. Idempotent — re-delivery of the same callback must be a no-op.

**Inbound webhook idempotency:**
- Use `idempotency:whatsapp:{statusId}` in Redis (TTL 24h) — drop the row if already processed. Tighter than the generic `Idempotency-Key` filter because Meta doesn't send one.

**New entities — none.**
- M7 reuses `AnnouncementRecipient.deliveryStatus + messageId` (already present from M6).
- No new migration.

**Configuration:**
- `WHATSAPP_API_BASE_URL` (default `https://graph.facebook.com/v20.0`)
- `WHATSAPP_PHONE_NUMBER_ID` (per-school override eventually; M7 = global)
- `WHATSAPP_ACCESS_TOKEN`
- `WHATSAPP_APP_SECRET` (HMAC key)
- `WHATSAPP_VERIFY_TOKEN` (GET subscribe handshake)
- `WHATSAPP_TEMPLATE_OTP_NAME` (default `parent_otp_v1`)
- `WHATSAPP_TEMPLATE_ANNOUNCEMENT_NAME` (default `school_announcement_v1`)
- `schoolbridge.outbox.relay.enabled=true` in prod profile; left `false` in `test` to avoid polling in tests.

**Permission helpers** — none new. Webhook is public, consumer paths run with system-tenant context (see open question on tenant binding for consumers).

**Mandatory tests:**
- **Webhook security:**
  - GET subscribe with correct verify token → 200 + challenge echo
  - GET subscribe with wrong verify token → 403
  - POST with valid HMAC → 200 + status row updated
  - POST with missing/invalid HMAC → 401
  - POST with idempotent re-delivery → second call is a no-op (recipient row unchanged after first update)
- **WhatsApp adapter unit tests** (fake RestClient via `MockRestServiceServer` or WireMock — pick one and use it consistently):
  - successful template send returns `messageId`
  - HTTP 4xx maps to `IntegrationException` and trips circuit breaker after threshold
  - retries with exponential backoff on 5xx
- **Dispatcher fallback:** WhatsApp fake throws → SMS fake invoked; assert audit row + recipient marked SENT.
- **Outbox publisher + consumer integration:** seed an `announcement.created` row, start the relay (force a manual tick), assert WhatsApp fake received N calls (N = recipientCount).
- **OTP swap:** `WhatsAppOtpDispatcher` invoked end-to-end for `POST /api/v1/parents/auth/request-otp` (CapturingOtpDispatcher pattern from `ParentAuthIntegrationTest` keeps working).
- **Cross-tenant safety of consumers:** an `announcement.created` for school A must not touch recipients of school B even though the consumer runs with no inbound TenantContext (verify by seeding both tenants' rows and asserting only school A's were updated).

**i18n keys** to add (ar + en + default):
- `error.whatsapp.unavailable` (downstream API errored, mapped to `IntegrationException`)
- `error.whatsapp.webhook.invalid_signature`
- `error.whatsapp.webhook.invalid_verify_token`
- `notification.whatsapp.template.parent_otp` (the literal body if you keep templates code-side; otherwise this lives in Meta's template console — see open question)
- `notification.whatsapp.template.school_announcement`

**Observability (required for the NFR-P2 path that M8 will rely on):**
- Micrometer counters: `whatsapp.send.success`, `whatsapp.send.failure`, `whatsapp.webhook.received`, `whatsapp.webhook.signature_invalid`, `notification.fallback.sms`.
- Histograms: `whatsapp.send.latency` (will become the dominant component of attendance alert latency in M8).
- Trace context propagated from outbox row's stored `traceId` through dispatcher → adapter so a single alert can be followed end-to-end.

---

## Open questions to resolve in your mini-plan

The handoff is opinionated where the SRS is clear, but the user owns these decisions — flag them in your mini-plan and wait for answers:

1. **OTP send path:** keep in-process (`OtpService.issue(...)` → `OtpDispatcher.dispatch(...)` directly, just swap the impl) **or** route through the outbox (`OtpService` writes an `otp.send` event, M7 consumer picks it up and sends)? In-process is simpler and the OTP latency budget is tight (no "fire and forget" needed); outbox gives retry-on-failure for free. Recommend **in-process for M7**, defer outbox migration to M14 hardening if OTP loss becomes a real incident.
2. **Tenant binding for consumers:** outbox consumers run with no inbound HTTP request → no `TenantContext`. Two options: (a) the consumer reads `schoolId` from the event payload and calls `TenantContext.runAs(schoolId, ...)` for each event; (b) the consumer uses a "system principal" path that bypasses the tenant filter. (a) is safer and matches the existing `runAs` helper — recommend (a).
3. **Template content storage:** Meta requires pre-approved template names with parameter slots. Two options: (a) the WhatsApp template body lives in Meta's console and we only pass template name + parameters; (b) we also keep the same body string in `messages_{lang}.properties` for SMS-fallback rendering. Recommend **(b)** — same i18n key drives both channels.
4. **Recalled announcement behavior:** when `announcement.recalled` fires, do we (a) attempt a WhatsApp template "this announcement was recalled" follow-up message, or (b) just stop in-flight sends and mark undelivered recipients FAILED, or (c) both? Recommend **(b) for M7**, add the recall-notification template in a later UX-tuning phase.
5. **SMS provider for the fallback:** is M7 in scope for a real SMS provider (Vonage / Twilio / a local Egyptian aggregator like Mobily / Etisalat), or just a fake + an interface? Recommend **fake-only in M7**; real provider is M14 ops work. The dispatcher tests run against the fake.
6. **WireMock vs MockRestServiceServer for the WhatsApp HTTP fake in tests:** MockRestServiceServer is in-process and binds tightly to a specific RestClient instance; WireMock is a stand-alone HTTP server (heavier but mimics real network behavior including timeouts/circuit-breaker scenarios). Recommend **WireMock** because we need realistic timeouts and slow-responses for Resilience4j tests; add it as `wiremock-standalone` test dep.

---

## The workflow (this is hard rule, do not skip)

1. **Before writing any code**, post a focused **mini-plan** (5–10 bullets) covering ports/adapters, endpoints, consumers, config, edge cases, file order — and the open questions above — then **wait for explicit confirmation**.
2. M7 has no migration. File order per module: dependency add (pom.xml) → config properties → port interfaces (`WhatsAppClient`, `SmsClient`) → fake impls (for tests) → cloud impl (`MetaCloudWhatsAppClient`) → `NotificationDispatcher` → consumer(s) → webhook controller → `OutboxPublisher` impl (RabbitMQ) → OTP dispatcher swap → tests → i18n.
3. Pause and let the user run/review at logical breakpoints — the WhatsApp adapter + WireMock setup is one breakpoint, the webhook + HMAC verify is another.
4. After coding, run `mvn test`. Module is not "done" until **all tests are green**.
5. Update `MEMORY.md` only with non-obvious findings worth carrying forward; do NOT memorize the Meta Cloud API field names (those belong in code/docs, not memory).

## Things to be extra careful about (M7 has more sharp edges than M6)

- **HMAC verification:** must be constant-time and must operate on the **raw request body bytes** before any JSON parsing. Spring's default decoding will corrupt the signature check — use a `ContentCachingRequestWrapper` or a dedicated filter that reads the body once and stashes it.
- **The 3-arg `UsernamePasswordAuthenticationToken` trap** (see `feedback_springsecurity_uptoken_trap`) applies if you build any auth tokens for consumer-side tenant binding. Don't call `setAuthenticated(true)`.
- **Outbox payload nullable fields** (see `feedback_outbox_audit_mapof_npe`). The consumer reads JSON via Jackson — `null` is fine. Don't reintroduce the `Map.of` trap on the consumer's downstream events.
- **Webhook is public.** `SecurityConfig.PUBLIC_PATHS` already allowlists `/integrations/whatsapp/webhook`. Don't add `@PreAuthorize` to it — that would 403 Meta's signed callbacks.
- **Resilience4j config goes in `application-*.yml`**, not Java code, so it's tunable per environment. CB threshold ≈ 50% in 20-call window; retry 3× with exponential backoff.

---

## How to start your first turn

1. Read this file, then the five memory files MEMORY.md links to, then §1.3 / §5.1 / §6 / §7 of `docs/IMPLEMENTATION_PLAN.md`.
2. Skim `common/outbox/OutboxRelay.java` + `OutboxPublisher.java` (where the `RabbitOutboxPublisher` plugs in).
3. Skim `identity/otp/LoggingOtpDispatcher.java` (replacement seam) and `identity/auth/principal/ParentPrincipal.java` (so you know what an OTP-issued token unlocks).
4. Skim `announcements/AnnouncementServiceImpl.java` — the `announcement.created` payload shape M7 must consume is right there.
5. Post the M7 mini-plan with the 6 open questions surfaced. Ask for answers + confirmation.
6. Do NOT start writing code until the user confirms.

That's it. Everything else is in the artifacts.
