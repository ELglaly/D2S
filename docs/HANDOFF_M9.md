
# SchoolBridge — Handoff to a Fresh Session (M9)

You are continuing a multi-session SchoolBridge backend build. **M1–M8 are complete and verified green** (165/165 tests pass: `mvn test`). This document is the **only thing you need to read before starting**, plus the files it points at.

M9 is **Homework** — the first slice of the M9–M11 fan-out (Homework ∥ Fees ∥ Messaging) per IMPLEMENTATION_PLAN §10. It's intentionally smaller than M8: no new SLA, no Rabbit topology beyond a reminder routing key, and the per-school quiet-hours / per-parent recipient materialization patterns are already proven in M6/M8. The interesting work is the **homework reminder pipeline** — a `@Scheduled` sweeper that fires daily at the school's configured `homeworkReminderTime`, fans out via the M7 dispatcher, and reuses the M8 quiet-hours deferral path.

---

## Authoritative state (read these first, in order)

1. **`~/.claude/projects/E--D2L/memory/MEMORY.md`** — auto-loaded. Pulls in:
   - `project_schoolbridge.md` — locked-in stack + decisions resolving SRS contradictions (Liquibase not Flyway, Spotless+SpotBugs, etc.)
   - `feedback_schoolbridge_workflow.md` — **strict module-by-module gated cadence the user expects** (mini-plan → confirm → code → mvn test green → next module)
   - `feedback_hibernate_filter_findbyid_bypass.md` — **every TenantEntity repo MUST override findById with a JPQL @Query** (Hibernate's @Filter doesn't apply to Session.find)
   - `feedback_springsecurity_uptoken_trap.md` — never call `setAuthenticated(true)` after the 3-arg `UsernamePasswordAuthenticationToken` constructor
   - `feedback_outbox_audit_mapof_npe.md` — **outbox + audit payloads always have nullable fields; build with `HashMap`, never `Map.of(...)`**
   - `feedback_restclient_jdk_factory_windows.md` — cloud adapters using RestClient must keep `SimpleClientHttpRequestFactory` on Windows JDK 23
   - `feedback_aip_colon_paths_dont_survive_clients.md` — **never write `POST /resource:action` in this codebase** (RestAssured/OkHttp/curl URL-encode `:` to `%3A` → Spring static-resource fallback → 500). M8 paid this tax; M9 must not re-litigate it. Use slash-style action verbs.

2. **`E:\D2L\docs\IMPLEMENTATION_PLAN.md`** — the master plan. Skim §10 module roadmap; the M9 slice is your scope. §2.3 (Homework entity sketch), §3.1 (migration order), §4 (Homework endpoint list).

3. **`E:\D2L\SchoolBridge_SRS_v1.0.md`** — requirements. The Homework section of §3, NFR-U1 (parent feed must paginate), §3.4 (quiet hours — applies to reminders).

4. **The code at `E:\D2L\src\main\java\com\schoolbridge\api\`** — actual structure. Especially:
   - `announcements/Announcement` + `AnnouncementRecipient` — **canonical materialized-recipient pattern.** `HomeworkRecipient` should mirror this 1:1 (per-parent row created at homework-publish time, queryable by `parentUserId` for the parent feed, status mutated by the M7 webhook later).
   - `announcements/AnnouncementServiceImpl.materializeRecipients` — the dedup-by-(parentUserId, studentId) loop for resolving `ParentStudentLink` → recipient rows.
   - `attendance/AttendanceAlertService` (M8) — **canonical quiet-hours-aware fan-out service.** `HomeworkReminderService` reuses the exact quiet-hours branching (`QuietHoursCalculator.isInQuietWindow` / `nextEndOfWindow`).
   - `attendance/AttendanceSweeper` (M8) — **canonical `@Scheduled` sweeper.** `HomeworkReminderSweeper` follows the same shape: outer iteration over `schoolRepository.findByStatus(ACTIVE, …)`, inner `TenantContext.runAs(schoolId, …)`, Redis SETNX dedup for "already fired today".
   - `integrations/AnnouncementSendService` + `rabbit/AnnouncementCreatedConsumer` — pattern if you decide to put homework reminders on the outbox path (see OQ3).
   - `classes/PermissionsHelper` (in `common/security/`) — `@perms.teacherTeaches(classId)` and `@perms.parentLinkedTo(studentId)` already exist. **No new helpers are required.**

---

## What's already shipped (M1–M8)

| Module | What's in it |
|---|---|
| M1 skeleton | Maven (Boot 3.3.5, Java 21), Liquibase, Docker/compose, JSON logging, i18n bundles, Testcontainers smoke test |
| M2 common infra | RFC 7807 advice + 8-exception hierarchy + i18n resolver, `BaseEntity` (UUIDv7) + `TenantEntity`, `TenantContext`, AES-256-GCM column converter + blind-index hasher, idempotency filter (Redis), outbox + audit (entities + `OutboxPublisher` port + `OutboxRelay` polling job, both relay-gated), `SecurityConfig` stateless skeleton, `PageResponse`, `RequestIdFilter` |
| M3 tenant | `School` aggregate (embedded `SchoolSettings` w/ jsonb fee offsets, `homeworkReminderEnabled`, `homeworkReminderTime`, `alertsRespectQuietHours`, `rosterDueByLocalTime`), super-admin `/api/v1/schools` CRUD, audit + outbox + domain events on every state change |
| M4 identity | `User extends TenantEntity` (encrypted name/phone + phone_hash blind index), `PlatformAdmin`, `RefreshToken`, JJWT RS256, parent OTP flow, `LoginRateLimiter`, `BearerAuthenticationFilter` + `TenantBindingFilter`, `TenantFilterAspect`, `TenantEntityArchUnitTest` build-time guard |
| M5 classes/students | 5 TenantEntity aggregates (`SchoolClass`, `Student`, `Enrollment`, `TeacherAssignment`, `ParentStudentLink`), `PermissionsHelper` bean (`@perms.teacherTeaches`, `@perms.parentLinkedTo`), CSV bulk import, cross-tenant isolation suites |
| M6 announcements | `Announcement` + `AnnouncementRecipient`, 6-endpoint controller, per-(parent, student) recipient materialization for SCHOOL/GRADE/CLASS/CUSTOM scopes, `announcement.created` + `announcement.recalled` outbox events with **HashMap payloads**, 3 `PermissionsHelper` helpers, full test suite |
| M7 WhatsApp | `WhatsAppClient` / `SmsClient` ports + `MetaCloudWhatsAppClient` + `LoggingSmsClient` + test fakes, `NotificationDispatcher` (per-recipient WhatsApp→SMS fallback), `RabbitConfig` + `RabbitOutboxPublisher` (event-type-prefix routing), `AnnouncementCreatedConsumer` + `AnnouncementRecalledConsumer`, webhook controller, `WhatsAppOtpDispatcher`, Micrometer counters |
| M8 attendance | `AttendanceRecord` + `AttendanceAlertRecipient`, 5 slash-style endpoints (`/attendance/mark`, `/attendance/mark-all-present`, `/attendance/roster`, `/attendance/history`, `/attendance/{id}/parent-response`), status-specific outbox events (`attendance.absent_alert` / `late_alert` / `excused_alert`), bulk-aggregated `attendance.bulk_marked`, `AttendanceAlertService` (per-school quiet-hours deferral, status-specific WhatsApp templates, per-parent recipient rows), 3-listener `AttendanceAlertConsumer`, NFR-P2 `attendance.alert.latency` Timer, `AttendanceSweeper` (`@Scheduled` for deferred-release every 60s + missed-roster detection every 15m via Redis SETNX dedup), `QuietHoursCalculator` helper, 44 new tests |

---

## Your task: Module 9 — Homework

Per IMPLEMENTATION_PLAN §2.3 (HomeworkItem sketch), §3.1 (migration `008-homework.sql`), §4 (Homework endpoints), §10 row M9–M11. **Independently demoable:** a teacher posts a homework item via `POST /api/v1/homework` → `HomeworkRecipient` rows are materialized for every parent of every student enrolled in the class → a parent calls `GET /api/v1/homework?childId=` and sees the feed → the next morning at the school's `homeworkReminderTime`, `HomeworkReminderSweeper` fans out a WhatsApp reminder per recipient via the M7 dispatcher.

### New aggregates (2)

**`HomeworkItem extends TenantEntity`** (008-homework.sql):
- `id` (UUIDv7), `schoolId`, `classId` (FK `school_classes`), `teacherId` (FK `users`), `subject` (varchar 200, **not encrypted** — searchable), `description` (AES-GCM encrypted via `AesGcmAttributeConverter`, varchar 8192), `attachmentKey` (varchar 512, nullable — wire the field, defer S3 storage to M14), `dueDate` (LocalDate), `status` (HomeworkStatus enum: DRAFT, PUBLISHED, ARCHIVED), `reminderSentAt` (Instant, nullable — set once the reminder sweeper has materialized recipients and started dispatch).
- **Indexes:** `(school_id, class_id, due_date)` for the teacher list view; `(school_id, status, due_date)` for the sweeper's "due soon and PUBLISHED" scan; `(school_id, teacher_id, created_at)` for the teacher's own-history view.
- **Not indexed:** `description`, `attachmentKey` — neither is queried.

**`HomeworkRecipient extends TenantEntity`**:
- `id` (UUIDv7), `schoolId`, `homeworkId` (FK `homework_items`), `parentUserId` (FK `users`), `studentId` (FK `students`), `deliveryStatus` (HomeworkDeliveryStatus enum: PENDING, DEFERRED, SENT, FAILED — same shape as `AttendanceAlertStatus`), `messageId` (varchar 255), `lastError` (varchar 256), `deferredUntil` (Instant, nullable).
- **Unique** `(homework_id, parent_user_id, student_id)` — a parent of two students in the same class gets two rows.
- **Indexes:** `(homework_id)` for the recipient roster view; `(school_id, parent_user_id, created_at desc)` for the parent feed query; `(school_id, deferred_until)` partial `WHERE deferred_until IS NOT NULL AND message_id IS NULL` for the sweeper's deferred-release scan; `(message_id)` partial `WHERE message_id IS NOT NULL` for the future webhook update path.

### Per-aggregate file order (within M9)

```
008-homework.sql (Liquibase changeset; add to db.changelog-master.yaml)
→ HomeworkStatus.java (enum: DRAFT, PUBLISHED, ARCHIVED)
→ HomeworkDeliveryStatus.java (enum: PENDING, DEFERRED, SENT, FAILED)
→ HomeworkItem.java (TenantEntity)
→ HomeworkRecipient.java (TenantEntity)
→ HomeworkItemRepository.java (JPQL findById override + status/dueDate range + teacher own-history)
→ HomeworkRecipientRepository.java (JPQL findById override + per-homework + per-parent feed + deferred-release scan + by-messageId for webhook)
→ dto/{CreateHomeworkRequest, UpdateHomeworkRequest, HomeworkResponse, HomeworkRecipientResponse, ParentHomeworkFeedEntry, HomeworkMapper}.java
→ HomeworkService + HomeworkServiceImpl (create/update/delete/publish/archive + recipient materialization)
→ HomeworkController (teacher CRUD + parent feed)
→ HomeworkReminderService (M8-style fan-out — quiet-hours-aware, status-specific template, mutates recipient row)
→ HomeworkReminderSweeper (@Scheduled — finds homework due for reminder + walks deferred recipients)
→ RabbitConfig additions (if you go the outbox route — see OQ3)
→ WhatsAppProperties.Template: one new accessor (`homework-reminder-name` → `homework_reminder_v1`)
→ messages_{,en,ar}.properties additions
→ tests
```

### Endpoints (per IMPLEMENTATION_PLAN §4)

**Use slash-style verbs** (`/homework/{id}/publish`, never `/homework/{id}:publish`) per [[feedback-aip-colon-paths-dont-survive-clients]].

| M | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/homework` | S (TEACHER for own class, SCHOOL_ADMIN for any) | Creates DRAFT or PUBLISHED depending on `publish` flag; if PUBLISHED, materializes `HomeworkRecipient` rows for every parent of every enrolled student in `classId`. Writes outbox `homework.published` (HashMap payload) only when initial status is PUBLISHED. |
| POST | `/api/v1/homework/{id}/publish` | S (TEACHER for own class, SCHOOL_ADMIN for any) | DRAFT → PUBLISHED transition; materializes recipients + writes `homework.published`; 409 if already PUBLISHED or ARCHIVED. |
| GET | `/api/v1/homework` | S | Paginated list filtered by `classId`, `status`, `dueDateFrom`, `dueDateTo`. Teacher sees only their own classes (`@perms.teacherTeaches(#classId)` SpEL); admin sees all. |
| GET | `/api/v1/homework/{id}` | S (admin any, teacher own classes) **or** P (parent linked to any student in that class) | Returns the item; for parents, also includes the parent's own `HomeworkRecipient` row (delivery + ack state). |
| PATCH | `/api/v1/homework/{id}` | S (TEACHER for own class while status is DRAFT or PUBLISHED, SCHOOL_ADMIN for any) | Updates `subject`, `description`, `attachmentKey`, `dueDate`. **Recipients are not re-materialized on update**; if dueDate moves forward into the next reminder window, the sweeper picks it up there. 409 on ARCHIVED. |
| DELETE | `/api/v1/homework/{id}` | S (TEACHER for own class, SCHOOL_ADMIN for any) | Soft-delete via status=ARCHIVED; writes outbox `homework.archived`; FK ON DELETE CASCADE keeps `homework_recipients` rows linked for parent-feed history. |
| GET | `/api/v1/homework?childId=` | P | Parent feed: returns the parent's `HomeworkRecipient` rows joined with `HomeworkItem` for the given child, paginated newest-first, filtered to status=PUBLISHED. Uses `@perms.parentLinkedTo(#childId)`. **Anti-enumeration: an unlinked child returns 404 from the service layer.** |
| POST | `/api/v1/homework/{id}/acknowledge` | P | Parent acknowledgement (mirror M6). Updates the parent's `HomeworkRecipient.acknowledgedAt`. 404 if the parent has no recipient row for this item. |

### Homework reminder pipeline

```
Teacher publishes homework with dueDate = D
  → HomeworkServiceImpl.publish(homeworkId)
      → status DRAFT → PUBLISHED
      → materialize HomeworkRecipient rows for linked parents of enrolled students
      → outbox.record("homework.published", { schoolId, homeworkId, classId, dueDate, recipientCount, traceId })  ← HashMap
      → audit.record("homework.published")

Daily at school-local homeworkReminderTime (per SchoolSettings, default 19:00 Cairo):
  HomeworkReminderSweeper.fireReminders()
    → for each ACTIVE school where settings.homeworkReminderEnabled = true
        AND school-local now is within ±15min of settings.homeworkReminderTime
      → TenantContext.runAs(schoolId, () -> {
          for each HomeworkItem where status=PUBLISHED AND dueDate IN [today, today+reminderHorizon]
              AND reminderSentAt IS NULL:
            → HomeworkReminderService.dispatchReminder(homeworkId)
                → for each HomeworkRecipient:
                    → if quiet-hours apply: markDeferred(end-of-window-instant)
                    → else: notificationDispatcher.dispatch(homework_reminder_v1, [subject, dueDateText])
                       → markSent(messageId) or markFailed(error)
                → if all recipients terminal: homework.reminderSentAt = now()
                → Micrometer counter `homework.reminder.sent` (tag: schoolId, status=ok|failed|deferred)
        })

Every 60s (same cadence as M8 deferred-release):
  HomeworkReminderSweeper.releaseDeferredReminders()
    → identical pattern to AttendanceSweeper.releaseDeferredAlerts()
    → finds HomeworkRecipient where deferredUntil <= now AND messageId is null
    → per-row: TenantContext.runAs(...) → HomeworkReminderService.releaseDeferred(recipientId)
```

**Idempotency** on the daily fire: a per-(homework, day) Redis SETNX key (`homework:reminder_fired:{homeworkId}:{date}`) with 36h TTL prevents the same item being fired twice within the daily window (the sweeper's actual cron cadence can be every 5 minutes inside the window without re-emitting).

**Why `reminderSentAt` AND a SETNX dedup?** The former is the durable, parent-visible "this homework reached me" stamp; the latter handles the race where the sweeper runs every 5 minutes and a new homework is published mid-window — the next sweep would see `reminderSentAt = null` and try again, which the SETNX safely deflects until the next day.

### Configuration

Reuse the M3 `SchoolSettings.homeworkReminderEnabled` + `homeworkReminderTime` fields (already on the schools table, already in the SchoolSettings embeddable since M3). **No new settings columns required.**

Reuse `SchoolSettings.alertsRespectQuietHours` for quiet-hours opt-in on homework reminders. **Open question (OQ1) covers whether to split this into a separate `remindersRespectQuietHours` flag** — the safer default is to reuse the same flag so school admins have one knob, not two.

New WhatsApp template name to wire:
- `schoolbridge.whatsapp.template.homework-reminder-name` (default `homework_reminder_v1`)

New Rabbit topology — minimal:
- `schoolbridge.rabbitmq.exchanges.homework` (default `schoolbridge.homework`)
- `schoolbridge.rabbitmq.queues.homework-reminder` (default `schoolbridge.whatsapp.homework-reminder`) — **only if you go the outbox route per OQ3**; otherwise skip and have the sweeper call `HomeworkReminderService.dispatchReminder` directly.

New sweeper config (mirror `schoolbridge.attendance.sweeper.*`):
- `schoolbridge.homework.sweeper.enabled` (default `true`; **off in `application-test.yml`**)
- `schoolbridge.homework.sweeper.reminder-window-minutes` (default `15` — the ±window around `homeworkReminderTime` in which sweeps qualify as "the daily fire")
- `schoolbridge.homework.sweeper.fire-cron` (default `0 */5 * * * *` — every 5 minutes; the SETNX dedup guarantees one fire per day per homework)
- `schoolbridge.homework.sweeper.deferred-release-rate` (default `60s`)
- `schoolbridge.homework.sweeper.reminder-horizon-days` (default `2` — fire reminders for items due today, tomorrow, and the day after; tighter horizons reduce parent noise, looser horizons help with weekend-spanning homework)
- `schoolbridge.homework.sweeper.dedup-ttl` (default `36h`)

### Permission helpers

**None new.** The four existing helpers cover the entire matrix:
- `@perms.teacherTeaches(classId)` — for the teacher-side write paths.
- `@perms.parentLinkedTo(studentId)` — for the parent feed + acknowledge.
- The existing `isAnnouncementSender(id)` / `parentReceivedAnnouncement(id)` are unrelated to homework but instructive — copy that template for `isHomeworkAuthor(id)` and `parentReceivedHomework(id)` **only if** the per-id authorization checks become awkward inline in SpEL.

### Mandatory tests

- **`HomeworkItem` cross-tenant isolation suite** (canonical pattern from `UserRepositoryIsolationTest`).
- **`HomeworkRecipient` cross-tenant isolation suite** (canonical pattern; mirror `AttendanceAlertRecipientRepositoryIsolationTest`).
- **`HomeworkControllerTest` matrix:**
  - create with admin → 201/200 + DRAFT or PUBLISHED based on `publish=true|false`
  - create with teacher on own class → 200/201
  - create with teacher on other class → 403
  - create with parent → 403
  - publish DRAFT → PUBLISHED + outbox `homework.published` + per-parent recipient rows materialized
  - publish already-PUBLISHED → 409
  - patch DRAFT → 200 (fields updated)
  - patch ARCHIVED → 409
  - delete → soft-archive + outbox `homework.archived`
  - parent feed for own child → 200 with paginated entries
  - parent feed for unlinked child → 404 (anti-enumeration, not 403)
  - get-one as parent for an item their child is on → 200 with recipient state included
  - get-one as parent for an item their child is NOT on → 404
  - acknowledge by recipient parent → 204 with `acknowledgedAt` set
  - acknowledge by non-recipient parent → 404
- **`HomeworkReminderService` fan-out test:** publish 1 homework with 3 enrolled students × 1 parent each → call `dispatchReminder` → fake adapter receives 3 sends with template `homework_reminder_v1`, subject + dueDate in params.
- **`HomeworkReminderSweeper` tests** (mirror `AttendanceSweeperTest`):
  - `fireReminders_outsideWindow_doesNotFire`
  - `fireReminders_insideWindow_firesOncePerSchool` (Redis SETNX dedup verified by calling sweep twice)
  - `fireReminders_skipsAlreadyFiredHomeworkItems` (those with `reminderSentAt != null`)
  - `fireReminders_skipsItemsWithDueDateOutOfHorizon`
  - `releaseDeferredReminders` — recipient with `deferredUntil < now` → released and dispatched; recipient with `deferredUntil > now` → untouched.
- **`HomeworkReminderConsumerCrossTenantIsolationTest`** — only if you go the outbox route per OQ3.
- **Quiet-hours-aware test:** school with `alertsRespectQuietHours=true` + `quietHoursStart..End` covering "now" → recipients become DEFERRED, no WhatsApp sends fire.

### i18n keys to add (ar + en + default)

- `error.homework.not_found`
- `error.homework.already_published` (publish on a PUBLISHED item)
- `error.homework.archived_cannot_modify`
- `error.homework.not_in_recipients` (parent acknowledges a homework they don't have a recipient row for)
- `notification.whatsapp.template.homework_reminder` — SMS-fallback body, e.g. `Homework: {0}. Due {1}.` (and Arabic equivalent).

### Observability

- **Micrometer counters** `homework.published` (tagged by school), `homework.reminder.sent` (tagged by channel + status: ok/failed/deferred), `homework.reminder.fired_schools` (the number of schools fired in a single sweep — sanity check for the cron).
- **Trace context** propagated through outbox payloads via `traceId` (read from MDC) — same as M6/M8.
- **No new SLA metric.** Homework reminders are not on the NFR-P2 path; the `attendance.alert.latency` histogram is the only critical-path latency metric in the system.

---

## Open questions to resolve in your mini-plan

The handoff is opinionated where the SRS is clear, but the user owns these decisions — flag them in your mini-plan and wait for answers:

1. **Quiet hours flag — reuse `alertsRespectQuietHours` or split?** Reusing the same flag is cleaner UX (one knob) but conflates "operational attendance alerts" with "non-urgent homework reminders" — they have different urgency profiles. Recommend: **reuse `alertsRespectQuietHours` for M9**; revisit only if a school admin complains.
2. **Reminder horizon: how many days ahead?** A homework with `dueDate = tomorrow` should clearly fire tonight. What about `dueDate = day-after-tomorrow`? Recommend: **default 2 days**, configurable per the property above; gives schools a knob without re-deploying.
3. **Reminder pipeline architecture: outbox-relay-consumer or direct sweeper-to-service?** The M6/M8 events were truly event-triggered (the trigger is an external HTTP request), so the outbox is the right seam. Homework reminders are **already scheduled** by the sweeper — the outbox adds a network hop without adding durability (the sweeper's `reminderSentAt` + SETNX dedup is already idempotent across restarts). Recommend: **direct sweeper-to-service call**, skip the Rabbit topology entirely for M9; revisit if reminder volume grows past a single-instance sweeper's throughput.
4. **DRAFT support — keep or simplify?** Teachers might want to compose a homework over multiple sessions before publishing. Recommend: **keep DRAFT**, it's a single enum value + a `publish` endpoint; deleting DRAFT support is easy if it's unused, adding it later requires a migration.
5. **Recipient materialization timing.** Materialize at publish time (M6 pattern, big up-front INSERT for large classes) or lazily at reminder time (smaller writes, but the parent feed query needs a JOIN through enrollment + parent_student_link, which is slower per-read)? Recommend: **materialize at publish time** — matches M6, makes the parent feed a trivial single-table read, and the upper bound on insert size (a few hundred recipients per item) is well within transaction limits.
6. **Acknowledgement opt-in via `requiresAck` flag (like M6)?** Or always allow acknowledge? Recommend: **add a `requiresAck` boolean** on `HomeworkItem` mirroring M6. Default `false` for homework (less heavy-handed than announcements); UI surfaces the ack button only when `requiresAck=true`.
7. **Attachment storage — implement S3 in M9 or defer?** The `attachmentKey` field is in the entity sketch, but no `StorageClient` adapter exists yet. Recommend: **accept and persist `attachmentKey` as an opaque string in M9; defer the actual upload/download wiring + pre-signed URLs to M14 hardening** (where the SRS object-store work is grouped). The teacher app can use a stub URL or skip attachments in the M9 demo.

---

## The workflow (this is hard rule, do not skip)

1. **Before writing any code**, post a focused **mini-plan** (5–10 bullets) covering the migration, entities, endpoints, reminder pipeline, file order — and the 7 open questions above — then **wait for explicit confirmation**.
2. File order per module: Liquibase migration → enums + entities → repositories (with JPQL `findById` overrides) → DTOs + mapper → service → controller → reminder service → sweeper → WhatsApp template config addition → tests → i18n.
3. Pause at logical breakpoints. Two natural ones for M9:
   - **BP-1:** migration + entities + repositories + DTOs + `HomeworkService/Controller` + parent feed + isolation/controller/idempotency tests → **green**.
   - **BP-2:** `HomeworkReminderService` + `HomeworkReminderSweeper` + i18n + reminder/sweeper tests → **green**.
4. After coding, run `mvn test`. Module is not "done" until **all tests are green**.
5. Update `MEMORY.md` only with non-obvious findings worth carrying forward; do NOT memorize routine details like enum members or column types.

---

## Things to be extra careful about (M9 has its own sharp edges)

- **Slash-style action verbs.** `POST /homework/{id}/publish`, **NOT** `POST /homework/{id}:publish`. The latter was the M8 trap and is now in [[feedback-aip-colon-paths-dont-survive-clients]]. If the IMPLEMENTATION_PLAN's `:bulk-import` style appears anywhere for users/students, that's M9's problem too if you touch those paths.
- **`@Filter` on `HomeworkItemRepository.findById` and `HomeworkRecipientRepository.findById`** — mandatory JPQL overrides per [[feedback-hibernate-filter-findbyid-bypass]]. The `TenantEntityArchUnitTest` will fail if you forget the `@Filter` annotation on the entity, but the findById bypass is silent — the only catch is the cross-tenant isolation test, which is why both isolation tests above are mandatory.
- **HashMap, not Map.of, for `homework.published` / `homework.archived` payloads.** `attachmentKey` and `dueDate-as-string` are the nullable-prone fields here. The same trap that bit M6 will bite M9 if you Map.of(...) for these. See [[feedback-outbox-audit-mapof-npe]].
- **`requiresAck` semantics.** If you adopt it (OQ6), make sure the parent feed query exposes the ack state cleanly and the `/acknowledge` endpoint is a no-op when `requiresAck=false` (don't 422 — silently succeed so the client doesn't have to branch).
- **Recipient materialization performance.** A school class can have 30–50 students × 1–2 parents each = up to 100 INSERTs per publish. Batch the saves: build the list of `HomeworkRecipient` objects then call `repository.saveAll(list)` once (mirror `AnnouncementServiceImpl.materializeRecipients`). Do NOT loop with individual `save` calls — at 100 per item it's noticeable, at 5000 per CUSTOM-scope announcement it's catastrophic.
- **`reminderSentAt` is set when fan-out STARTS, not when it COMPLETES.** Otherwise the sweeper's "5 minutes later" tick will see the item as unfired (because dispatch isn't done) and try again. Setting `reminderSentAt = now()` immediately before the per-recipient loop makes the daily-dedup work; deferred-released rows later don't re-stamp it (they're a different lifecycle).
- **Parent feed pagination.** NFR-U1 wants the parent app to scroll a feed; the index on `(school_id, parent_user_id, created_at desc)` is the load-bearing one. JPQL: `select r from HomeworkRecipient r where r.parentUserId = :pid and r.studentId = :childId order by r.createdAt desc`, then `r.homeworkId` to project the item details via a secondary fetch or a JPA constructor projection — see the M8 `AttendanceRosterEntry` for the projection pattern.
- **`homework.published` consumer is not in M9.** The outbox event is written for downstream (M12 reporting, M13 audit) but no consumer subscribes. Rabbit will drop it on the floor at the exchange — same as `attendance.bulk_marked` and `attendance.roster_missed` today. That's intentional.

---

## How to start your first turn

1. Read this file, then the memory files MEMORY.md links to (especially [[feedback-aip-colon-paths-dont-survive-clients]] and [[feedback-outbox-audit-mapof-npe]]), then §2.3 / §3.1 / §4 (Homework) of `docs/IMPLEMENTATION_PLAN.md`.
2. Skim `announcements/AnnouncementServiceImpl.materializeRecipients` — the **canonical pattern** for per-parent fan-out at publish time.
3. Skim `attendance/AttendanceAlertService` + `attendance/AttendanceSweeper` — the canonical quiet-hours-aware fan-out and the canonical scheduled sweeper. `HomeworkReminderService` and `HomeworkReminderSweeper` are direct cousins.
4. Skim `classes/PermissionsHelper.java` — confirm `teacherTeaches` and `parentLinkedTo` are the helpers you'll reference from `@PreAuthorize`.
5. Post the M9 mini-plan with the 7 open questions surfaced. Ask for answers + confirmation.
6. Do NOT start writing code until the user confirms.

That's it. Everything else is in the artifacts.
