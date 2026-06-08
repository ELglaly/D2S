
# SchoolBridge — Handoff to a Fresh Session (M8)

You are continuing a multi-session SchoolBridge backend build. **M1–M7 are complete and verified green** (121/121 tests pass: `mvn test`). This document is the **only thing you need to read before starting**, plus the files it points at.

M8 is the **critical-path module**: the attendance alert pipeline is the SRS's highest-value SLA (NFR-P2: p95 mark→dispatch < 60s; overall mark→parent ≤ 5 min) and the reason the M2 outbox + M7 WhatsApp adapter were built ahead of the consumer features that need them.

---

## Authoritative state (read these first, in order)

1. **`~/.claude/projects/E--D2L/memory/MEMORY.md`** — auto-loaded. Pulls in:
   - `project_schoolbridge.md` — locked-in stack + decisions resolving SRS contradictions (Liquibase not Flyway, Spotless+SpotBugs, etc.)
   - `feedback_schoolbridge_workflow.md` — **strict module-by-module gated cadence the user expects** (mini-plan → confirm → code → mvn test green → next module)
   - `feedback_hibernate_filter_findbyid_bypass.md` — **every TenantEntity repo MUST override findById with a JPQL @Query** (Hibernate's @Filter doesn't apply to Session.find)
   - `feedback_springsecurity_uptoken_trap.md` — never call `setAuthenticated(true)` after the 3-arg `UsernamePasswordAuthenticationToken` constructor
   - `feedback_outbox_audit_mapof_npe.md` — **outbox + audit payloads always have nullable fields; build with `HashMap`, never `Map.of(...)`**
   - `feedback_restclient_jdk_factory_windows.md` — cloud adapters using RestClient must keep `SimpleClientHttpRequestFactory` on Windows JDK 23 (M7's `MetaCloudWhatsAppClient` is the canonical example)

2. **`E:\D2L\docs\IMPLEMENTATION_PLAN.md`** — the master plan. Skim §10 module roadmap; the M8 slice is your scope. §2.3 has the entity sketch, §3.1/§3.2 the migration order/indexes, §4 (Attendance section) the endpoint list, §6 (Attendance alert pipeline) the SLA path.

3. **`E:\D2L\SchoolBridge_SRS_v1.0.md`** — requirements. §3.3 (attendance flow), §3.4 (quiet hours — relevant to OQ1 below), NFR-P2 (5-minute end-to-end alert SLA — **the binding constraint for M8**).

4. **The code at `E:\D2L\src\main\java\com\schoolbridge\api\`** — actual structure. Especially:
   - `common/outbox/OutboxEventRecorder` — write `attendance.*` payloads through this (HashMap, not Map.of)
   - `integrations/AnnouncementSendService` (M7) — **the canonical template for `AttendanceAlertService`**: load batches under `TenantContext.runAs`, render template params + SMS body, call `NotificationDispatcher.dispatch(...)`, mutate domain rows on result.
   - `integrations/NotificationDispatcher` (M7) — already does WhatsApp→SMS fallback with per-recipient sliding-window failures. Reuse as-is.
   - `integrations/rabbit/RabbitConfig` + `RabbitOutboxPublisher` (M7) — add the `attendance.*` exchange/queues/bindings here; the publisher's `exchangeFor(eventType)` switch needs an `attendance` arm.
   - `integrations/rabbit/AnnouncementCreatedConsumer` — the pattern for the new `AttendanceMarkedAbsentConsumer` (parses payload, runs `TenantContext.runAs(schoolId, …)`, calls a domain `*SendService`).
   - `classes/PermissionsHelper` — `@perms.teacherTeaches(classId)` and `@perms.parentLinkedTo(studentId)` already exist. M8 reuses them; no new helpers required.
   - `announcements/AnnouncementRecipient` (M7 mutators) — pattern for adding `markSent(messageId)` / `markFailed()` mutators on the new entity if you choose to materialize a per-(absence, parent) outbound row. **Open question (OQ3) covers whether to materialize.**

---

## What's already shipped (M1–M7)

| Module | What's in it |
|---|---|
| M1 skeleton | Maven (Boot 3.3.5, Java 21), Liquibase, Docker/compose, JSON logging, i18n bundles, Testcontainers smoke test |
| M2 common infra | RFC 7807 advice + 8-exception hierarchy + i18n resolver, `BaseEntity` (UUIDv7) + `TenantEntity`, `TenantContext`, AES-256-GCM column converter + blind-index hasher, idempotency filter (Redis), outbox + audit (entities + `OutboxPublisher` port + `OutboxRelay` polling job, both relay-gated), `SecurityConfig` stateless skeleton, `PageResponse`, `RequestIdFilter` |
| M3 tenant | `School` aggregate (embedded `SchoolSettings` w/ jsonb fee offsets), super-admin `/api/v1/schools` CRUD, audit + outbox + domain events on every state change |
| M4 identity | `User extends TenantEntity` (encrypted name/phone + phone_hash blind index), `PlatformAdmin`, `RefreshToken`, JJWT RS256 (ephemeral dev keypair fallback), parent OTP flow (Redis-backed, `LoggingOtpDispatcher` placeholder), `LoginRateLimiter`, `BearerAuthenticationFilter` + `TenantBindingFilter`, `TenantFilterAspect`, `TenantEntityArchUnitTest` build-time guard, mandatory cross-tenant isolation suite for `User` |
| M5 classes/students | 5 TenantEntity aggregates (`SchoolClass`, `Student`, `Enrollment`, `TeacherAssignment`, `ParentStudentLink`), `PermissionsHelper` bean (`@perms.teacherTeaches`, `@perms.parentLinkedTo`), CSV bulk import, per-aggregate slice + integration tests, 5 cross-tenant isolation suites |
| M6 announcements | 2 TenantEntity aggregates (`Announcement`, `AnnouncementRecipient`), CSV-style 6-endpoint controller, per-(parent, student) recipient materialization for SCHOOL/GRADE/CLASS/CUSTOM scopes, `announcement.created` + `announcement.recalled` outbox events with **HashMap payloads** (Map.of NPE'd on nullable attachmentKey), 3 new `PermissionsHelper` helpers, 2 cross-tenant isolation suites, controller matrix + fanout + parent-ack integration tests |
| M7 WhatsApp | `WhatsAppClient` / `SmsClient` ports + `MetaCloudWhatsAppClient` (RestClient + Resilience4j CB/Retry) + `LoggingSmsClient` placeholder + test fakes (`@Profile("test") @Primary`), `NotificationDispatcher` (per-recipient 2-failures-in-10m → SMS fallback via Redis INCR/TTL), `RabbitConfig` + `RabbitOutboxPublisher` (event-type-prefix routing), `AnnouncementCreatedConsumer` + `AnnouncementRecalledConsumer` (TenantContext.runAs(schoolId, …) before recipient loads — cross-tenant safe), webhook controller at `/integrations/whatsapp/webhook` (GET subscribe handshake + POST HMAC-SHA256 over raw `@RequestBody byte[]` + Redis SETNX idempotency on `{messageId}:{status}`), `WhatsAppOtpDispatcher` replaces `LoggingOtpDispatcher` in non-test profiles, WireMock adapter tests, dispatcher fallback tests, outbox→consumer integration + cross-tenant safety tests, `Micrometer` counters (`whatsapp.send.success/failure`, `whatsapp.webhook.received/signature_invalid`, `notification.fallback.sms`) |

---

## Your task: Module 8 — Attendance (the NFR-P2 path)

Per `docs/IMPLEMENTATION_PLAN.md` §2.3 / §3.2 / §4 (Attendance) / §6 (Attendance alert pipeline) and SRS §3.3 / NFR-P2. **Independently demoable:** a teacher marks a student ABSENT via `POST /api/v1/attendance:mark` → the row is persisted with an outbox `attendance.absent_alert` event → the M7 dispatcher fans out a WhatsApp template to the linked parents → an end-to-end metric records the mark→dispatch latency for the NFR-P2 SLO.

### New aggregates (1)

**`AttendanceRecord extends TenantEntity`** (007-attendance.sql):
- `id` (UUIDv7), `schoolId`, `studentId`, `classId`, `date` (LocalDate), `status` (AttendanceStatus enum: PRESENT, ABSENT, LATE, EXCUSED), `markedByUserId`, `markedAt` (Instant), `parentResponse` (String, encrypted via `AesGcmAttributeConverter`, nullable), `parentRespondedAt` (Instant, nullable), `alertSentAt` (Instant, nullable — set by the consumer once at least one parent dispatch has been attempted; used by NFR-P2 metric).
- **Unique** `(school_id, student_id, class_id, date)` — the second mark for the same student/class/day must update, not duplicate.
- **Indexes** `(school_id, class_id, date)` for the roster query and `(school_id, student_id, date)` for the history query.
- **NEW indexed/jsonb fields are not required for M8** — keep the table lean.

### Per-aggregate file order (within M8)

```
007-attendance.sql (Liquibase changeset; add to db.changelog-master.yaml)
→ AttendanceStatus.java (enum)
→ AttendanceRecord.java (TenantEntity)
→ AttendanceRecordRepository.java (JPQL findById override per [[feedback-hibernate-filter-findbyid-bypass]])
→ dto/{MarkAttendanceRequest, MarkAllPresentRequest, AttendanceRosterEntry, AttendanceHistoryEntry, ParentResponseRequest, AttendanceMapper}.java
→ AttendanceService + AttendanceServiceImpl
→ AttendanceAlertService (the M7-style fan-out service — modeled after AnnouncementSendService)
→ AttendanceController
→ integrations/rabbit/AttendanceAbsentAlertConsumer
→ RabbitConfig additions (attendance exchange, queue, binding, DLQ)
→ RabbitOutboxPublisher.exchangeFor(): add "attendance" → "schoolbridge.attendance"
→ messages_{,en,ar}.properties additions
→ tests
```

### Endpoints (per IMPLEMENTATION_PLAN §4)

| M | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/v1/attendance/roster?classId=&date=` | S (TEACHER for own class, SCHOOL_ADMIN for any) | Returns a row per enrolled student with their current `AttendanceStatus` (or null if not yet marked). Uses left-join via JPQL on `Enrollment` ↔ `AttendanceRecord`. |
| POST | `/api/v1/attendance:mark` | S (TEACHER for own class, SCHOOL_ADMIN for any) | Idempotent on `(schoolId, studentId, classId, date)`. Upserts the row. Body: `{studentId, classId, date, status}`. On status transition that creates a new ABSENT (PRESENT→ABSENT, null→ABSENT, LATE→ABSENT) **and only on transitions to ABSENT**, write an `attendance.absent_alert` outbox event (HashMap payload). Other transitions write an `attendance.marked` event for downstream consumers (none in M8; reserved for M12 reporting). |
| POST | `/api/v1/attendance:mark-all-present` | S | Body: `{classId, date}`. Resolves the class's `Enrollment` set under `TenantContext`, upserts every enrolled student to PRESENT (skip rows already PRESENT). No alerts fire. One `attendance.marked` outbox event per class (aggregated), or per row — see **OQ4**. |
| GET | `/api/v1/attendance/history?studentId=&from=&to=` | S (admin/teacher only sees own classes' rows) **or** P (parent sees own child only) | Returns the student's records in the date range. For parents: requires `@perms.parentLinkedTo(studentId)`. |
| POST | `/api/v1/attendance/{id}/parent-response` | P | Body: `{response}`. Updates `parentResponse` + `parentRespondedAt`. Only valid on rows where `parent_user_id` is linked via `ParentStudentLink` (404 if not the parent's child; 422 if status is not ABSENT/LATE). Adds an audit log entry. |

### Alert pipeline (the SRS-binding path)

```
POST :mark (status=ABSENT)
  → AttendanceServiceImpl.markAbsent(...)
      → AttendanceRecordRepository.upsert (within @Transactional)
      → outbox.record("attendance.absent_alert", {schoolId, recordId, studentId, classId, date, language, traceId, markedAt})  ← HashMap
      → audit.record("attendance.marked")
  → after commit:
      OutboxRelay (5s poll) → RabbitOutboxPublisher → schoolbridge.attendance / attendance.absent_alert
  → AttendanceAbsentAlertConsumer.onMessage(...)
      → TenantContext.runAs(schoolId, () -> attendanceAlertService.dispatchAbsentAlert(recordId))
  → AttendanceAlertService.dispatchAbsentAlert(recordId)
      → load AttendanceRecord
      → resolve linked parents via ParentStudentLinkRepository
      → for each parent:
          notificationDispatcher.dispatch( {phone, AR, template=attendance_absent_v1, params=[studentFirstName, dateFormatted], smsBody=<rendered i18n>} )
      → record AttendanceRecord.alertSentAt = now()
      → emit Micrometer histogram attendance.alert.latency = (now() − markedAt)
```

**No new entity** is required for the per-parent outbound row. Parent responses (POST `/{id}/parent-response`) update the **AttendanceRecord** itself, not a per-parent join row — this matches the IMPLEMENTATION_PLAN §2.3 data model (single `parentResponse` field on the record). See **OQ3** for the materialization trade-off if you want one outbound row per parent for delivery tracking.

### Configuration

Reuse M7 config. **One new template name** to add:
- `schoolbridge.whatsapp.template.attendance-absent-name` (default `attendance_absent_v1`)

And one new exchange/queue:
- `schoolbridge.rabbitmq.exchanges.attendance` (default `schoolbridge.attendance`)
- `schoolbridge.rabbitmq.queues.attendance-absent-alert` (default `schoolbridge.whatsapp.attendance-absent-alert`)

These follow the M7 naming convention; the `WhatsAppProperties.Template` nested class needs one new accessor; `RabbitConfig` needs three new beans (exchange + queue + binding) plus DLQ wiring.

### Permission helpers

**None new.** The two existing helpers cover the entire matrix:
- `@perms.teacherTeaches(classId)` — for mark / mark-all-present (TEACHER), implicitly granted to SCHOOL_ADMIN by role check.
- `@perms.parentLinkedTo(studentId)` — for parent history reads and parent-response writes.

### Mandatory tests

- **AttendanceRecord cross-tenant isolation suite** (canonical pattern from `UserRepositoryIsolationTest` — mandatory per `project_schoolbridge.md`).
- **`AttendanceController` matrix:**
  - mark with admin on any class → 200/201 + record persisted
  - mark with teacher on **own** class → 200/201
  - mark with teacher on **other** class → 403
  - mark with parent → 401/403
  - mark idempotent: same (student, class, date, status) twice → second call returns the same record id, only one outbox event written
  - mark transition PRESENT→ABSENT writes `attendance.absent_alert`; ABSENT→PRESENT writes `attendance.marked` only
  - roster: returns 30 rows for a class of 30 enrolled students, with status nullable for unmarked
  - mark-all-present skips already-PRESENT rows (no duplicate outbox events)
  - history: parent sees only own child; teacher sees only own classes; admin sees all
  - parent-response on linked child → 200; on unlinked child → 403/404
- **`AttendanceAlertService` fan-out test:** seed 3 parents linked to 1 absent student → fake adapter receives 3 sends with template `attendance_absent_v1`, student first name + formatted date in params.
- **Consumer→dispatcher integration test:** call `AttendanceAbsentAlertConsumer.onMessage(json bytes)` directly (the M7 pattern in `AnnouncementCreatedConsumerIntegrationTest`), assert recipients dispatched and `alertSentAt` set.
- **Cross-tenant safety of the alert consumer:** seed school A and school B, fire an event for A only, assert school B's rows untouched (the M7 `ConsumerCrossTenantIsolationTest` pattern).
- **NFR-P2 latency observation test (synthetic):** mark → invoke consumer immediately → assert `attendance.alert.latency` histogram has 1 record < 60_000ms. A real load test (k6) is M14.

### i18n keys to add (ar + en + default)

- `error.attendance.invalid_status` (rejecting a transition like FAILED→PRESENT or empty status)
- `error.attendance.record_not_found`
- `error.attendance.parent_not_linked` (parent-response by an unlinked parent)
- `error.attendance.parent_response_not_allowed` (parent-response on a PRESENT row)
- `notification.whatsapp.template.attendance_absent` — body for the SMS fallback, e.g. `{0} was marked absent on {1}. Please reply if there's a reason we should know.` (and Arabic equivalent)

### Observability (the NFR-P2 path is the whole point of this metric set)

- **Micrometer histogram** `attendance.alert.latency` — milliseconds from `markedAt` to the moment the consumer finishes calling `notificationDispatcher.dispatch(...)` for the **last** linked parent. Use a `Timer.Sample` started in the consumer (the consumer can re-derive `markedAt` from the outbox payload — DON'T re-read `now()` in the producer because the producer has no consumer-side knowledge). Buckets explicitly include 60s so the p95 threshold is dashboard-visible.
- **Counters** `attendance.marked` (tagged by `status`), `attendance.alert.sent` (tagged by `channel`), `attendance.alert.failed`.
- **Trace context** propagated through the outbox payload's `traceId` field so a single mark → consumer → dispatcher → adapter span tree is reconstructable per absent student. The M7 `attendance.absent_alert` payload **must include `traceId`** (read from `MDC.get("traceId")` at the time of mark). Consumer puts it back on MDC before calling the dispatcher.

---

## Open questions to resolve in your mini-plan

The handoff is opinionated where the SRS is clear, but the user owns these decisions — flag them in your mini-plan and wait for answers:

1. **Quiet hours for attendance alerts?** `SchoolSettings.quietHours` is wired (M3) and the SRS §3.4 mentions quiet hours for messaging. Attendance is **operational** (parent needs to know their kid isn't in class — time-critical). Recommend: **NO quiet hours for attendance alerts in M8.** Add a `respectQuietHours=false` constant in the alert service so M11 messaging can wire its own `respectQuietHours=true` path without reopening attendance.
2. **Which statuses trigger an alert?** SRS implies ABSENT only. Recommend: **ABSENT only fires `attendance.absent_alert`.** LATE writes `attendance.marked` (no alert) — schools that want LATE notifications get them after M12. EXCUSED writes `attendance.marked` only (school already has the reason).
3. **Materialize per-parent outbound rows?** Two options: (a) like M6 announcements, materialize one `AttendanceAlertRecipient` row per linked parent so each has its own `deliveryStatus + messageId` (clean for the webhook to update per parent); (b) keep one record per (student, date) and store an aggregate `alertSentAt` only (lighter, but the webhook can't easily map a Meta `messageId` back to an AttendanceRecord without an additional message_id → record join). Recommend **(b) for M8 with a deferred (a) in M14** — the webhook M7 already updates `AnnouncementRecipient` by `messageId`, so it would naturally extend to `AttendanceAlertRecipient` later. For now, treat the alert send as fire-and-forget at the recipient level; aggregated `alertSentAt` is sufficient for NFR-P2 and `mark-attendance` audit needs.
4. **Outbox event per row or per bulk operation in mark-all-present?** Bulk marking 30 students PRESENT shouldn't write 30 outbox rows (storm). Recommend: **one outbox event per row for ABSENT/LATE/EXCUSED transitions, none for PRESENT bulk-set** (no alert, no downstream consumer needs to see "Adam was marked present"). Audit log gets one entry per row regardless (audit ≠ outbox).
5. **Mark idempotency semantics:** same student/class/date with same status → no-op or replace? Recommend: **same status = no-op (200 + original record, no new outbox row), different status = update + new outbox row.** Matches §4 expectations and avoids re-firing the alert on a teacher's accidental re-submit.
6. **Background "missed roster" sweep:** SRS doesn't require it, but ops will eventually want a job that flags classes with no roster submitted by HH:MM local time. Recommend: **NOT in M8.** Defer to M14 hardening; if shipped earlier it duplicates teacher workflows we haven't observed yet.
7. **Encrypted `parentResponse` size:** the encrypted column is AES-256-GCM + Base64 → ~4/3× plaintext + IV/tag overhead. The SRS doesn't give a max length; pick a sane plaintext limit (e.g. 500 chars) → 1024-byte ciphertext column. Recommend **1024 bytes**, same as `User.name`.

---

## The workflow (this is hard rule, do not skip)

1. **Before writing any code**, post a focused **mini-plan** (5–10 bullets) covering the migration, entities, endpoints, alert pipeline, outbox event types, file order — and the 7 open questions above — then **wait for explicit confirmation**.
2. File order per module: Liquibase migration → enum + entity → repository (with JPQL `findById` override) → DTOs + mapper → service → controller → alert service → consumer → RabbitConfig additions → publisher routing arm → tests → i18n.
3. Pause and let the user run/review at logical breakpoints — the entity + roster/mark endpoints + idempotency tests is one breakpoint; the alert pipeline (consumer + dispatcher + NFR-P2 metric) is another.
4. After coding, run `mvn test`. Module is not "done" until **all tests are green**.
5. Update `MEMORY.md` only with non-obvious findings worth carrying forward; do NOT memorize routine details like enum members or column types.

---

## Things to be extra careful about (M8 has unique sharp edges)

- **NFR-P2 SLA is the whole point of this module.** Any synchronous work on the mark path (validation, audit, outbox write) goes inside the request transaction; any latency-introducing work (loading parents, rendering templates, calling adapters) goes inside the consumer, **after the user's HTTP response has been sent**. Keep `AttendanceServiceImpl.mark(...)` lean.
- **Idempotency on bulk operations.** `mark-all-present` MUST be safe to retry without writing duplicate outbox events for already-PRESENT students. Inside the same transaction: query existing rows, compute the delta, only upsert + outbox for the delta. The `IdempotencyFilter` (M2) handles the HTTP-level retry case — but the *internal* skip-already-PRESENT logic is what avoids the "30 outbox rows per retry" footgun.
- **`@Filter` on AttendanceRecordRepository.findById** — mandatory JPQL override per [[feedback-hibernate-filter-findbyid-bypass]]. The `TenantEntityArchUnitTest` will fail if you forget the `@Filter` activation, but the findById bypass is silent — the only catch is the cross-tenant isolation test.
- **HashMap, not Map.of, for `attendance.absent_alert` payload** — `traceId` and `attachmentKey`-style nullable fields are exactly the case from [[feedback-outbox-audit-mapof-npe]].
- **Trace context propagation.** Read `MDC.get("traceId")` at mark time, write it into the outbox payload, restore it on the consumer side before invoking the dispatcher. Without this you cannot follow a single "Adam marked absent at 09:02 → parent got WhatsApp at 09:02:18" in the tracing UI, which is the whole NFR-P2 debugging story.
- **Roster query is read-heavy** — it left-joins enrollment to attendance for a single date. Use a JPQL projection (not a full entity hydration of the unrelated columns) to avoid loading every enrolled student's encrypted name twice per request. The unique index `(school_id, class_id, date)` is what makes this cheap.
- **Parent-response on someone else's child** — must 404 (not 403) per the SRS's anti-enumeration posture for parent endpoints. The `@perms.parentLinkedTo(studentId)` SpEL helper returns `false` and Spring Security translates to 403; you'll need a service-level check that throws `NotFoundException` first.

---

## How to start your first turn

1. Read this file, then the six memory files MEMORY.md links to, then §2.3 / §3.2 / §4 (Attendance) / §6 (Attendance alert pipeline) of `docs/IMPLEMENTATION_PLAN.md`, then SRS §3.3 + NFR-P2.
2. Skim `integrations/AnnouncementSendService.java` — the **canonical pattern** for `AttendanceAlertService`. The shape is: load page → for each row, resolve phone → build `DispatchRequest` → `NotificationDispatcher.dispatch(...)` → mutate domain row.
3. Skim `integrations/rabbit/AnnouncementCreatedConsumer.java` + `RabbitConfig.java` + `RabbitOutboxPublisher.java` — the seam where you add the `attendance` exchange/queue/routing.
4. Skim `classes/PermissionsHelper.java` — confirm `teacherTeaches` and `parentLinkedTo` are the helpers you'll reference from `@PreAuthorize`.
5. Post the M8 mini-plan with the 7 open questions surfaced. Ask for answers + confirmation.
6. Do NOT start writing code until the user confirms.

That's it. Everything else is in the artifacts.
