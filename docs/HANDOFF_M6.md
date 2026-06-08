
# SchoolBridge — Handoff to a Fresh Session (M6)

You are continuing a multi-session SchoolBridge backend build. **M1–M5 are complete and verified green** (78/78 tests pass: `mvn test`). This document is the **only thing you need to read before starting**, plus the files it points at.

---

## Authoritative state (read these first, in order)

1. **`~/.claude/projects/E--D2L/memory/MEMORY.md`** — auto-loaded. Pulls in:
   - `project_schoolbridge.md` — locked-in stack + decisions resolving SRS contradictions (Liquibase not Flyway, Spotless+SpotBugs, etc.)
   - `feedback_schoolbridge_workflow.md` — **strict module-by-module gated cadence the user expects** (mini-plan → confirm → code → mvn test green → next module)
   - `feedback_hibernate_filter_findbyid_bypass.md` — **every TenantEntity repo MUST override findById with a JPQL @Query** (Hibernate's @Filter doesn't apply to Session.find)
   - `feedback_springsecurity_uptoken_trap.md` — never call `setAuthenticated(true)` after the 3-arg `UsernamePasswordAuthenticationToken` constructor

2. **`E:\D2L\docs\IMPLEMENTATION_PLAN.md`** — the master plan. Skim the §10 module roadmap; the M6 slice is your scope.

3. **`E:\D2L\SchoolBridge_SRS_v1.0.md`** — requirements. §3.2 (Announcements) + NFR-P4 (5k-recipient fan-out) are M6-relevant.

4. **The code at `E:\D2L\src\main\java\com\schoolbridge\api\`** — actual structure. Especially:
   - `classes/` (M5 — templates for new TenantEntity aggregate + permission SpEL guards)
   - `common/outbox/` (`OutboxEventRecorder` — the same path attendance will reuse in M8)
   - `common/security/PermissionsHelper.java` — extend this bean with the new M6 helpers

---

## What's already shipped (M1–M5)

| Module | What's in it |
|---|---|
| M1 skeleton | Maven (Boot 3.3.5, Java 21), Liquibase, Docker/compose, JSON logging, i18n bundles, Testcontainers smoke test |
| M2 common infra | RFC 7807 advice + 8-exception hierarchy + i18n resolver, `BaseEntity` (UUIDv7) + `TenantEntity`, `TenantContext`, AES-256-GCM column converter + blind-index hasher, idempotency filter (Redis), outbox + audit (entities + 002-common-infra.sql), `SecurityConfig` stateless skeleton, `PageResponse`, `RequestIdFilter` |
| M3 tenant | `School` aggregate (embedded `SchoolSettings` w/ jsonb fee offsets), 003-tenant.sql, super-admin `/api/v1/schools` CRUD, audit + outbox + domain events on every state change |
| M4 identity | `User extends TenantEntity` (encrypted name/phone + phone_hash blind index), `PlatformAdmin`, `RefreshToken`, JJWT RS256 (ephemeral dev keypair fallback), parent OTP flow (Redis-backed, logging dispatcher; real WhatsApp send lands in M7), `LoginRateLimiter` (5 fails/15min), `BearerAuthenticationFilter` + `TenantBindingFilter`, `TenantFilterAspect` (activates Hibernate filter on Spring Data repo calls under active tx), `TenantEntityArchUnitTest` build-time guard, mandatory cross-tenant isolation suite for `User` |
| M5 classes/students | 5 TenantEntity aggregates (`SchoolClass`, `Student`, `Enrollment`, `TeacherAssignment`, `ParentStudentLink`), 005-classes-students.sql with `ON DELETE CASCADE` on M5 FKs, `PermissionsHelper` bean (`@perms.teacherTeaches`, `@perms.parentLinkedTo`), CSV bulk import (`POST /students:bulk-import`, hand-rolled parser), per-aggregate slice + integration tests, 5 cross-tenant isolation suites |

---

## Your task: Module 6 — Announcements

Per `docs/IMPLEMENTATION_PLAN.md` §10. Independently demoable: SCHOOL_ADMIN creates an announcement targeting CLASS/GRADE/SCHOOL/CUSTOM, recipients are materialized, outbox event fires for downstream WhatsApp dispatch (the M7 consumer will pick it up), parents acknowledge.

**Entities (all TenantEntity):**
- `Announcement` — `senderId (FK→users), scopeType (enum: SCHOOL/GRADE/CLASS/CUSTOM), scopeId (nullable UUID — classId for CLASS, null for SCHOOL, grade string→hashed-to-UUID? see open question below), language (enum: AR/EN), body (AES-GCM), attachmentKey (S3 key, nullable), requiresAck (boolean), scheduledFor (nullable Instant), status (enum: DRAFT/SCHEDULED/SENDING/SENT/RECALLED)`
- `AnnouncementRecipient` — `announcementId (FK→announcements), parentUserId (FK→users), studentId (FK→students), deliveryStatus (enum: QUEUED/SENT/DELIVERED/READ/FAILED), acknowledgedAt (nullable Instant), messageId (nullable — set when WhatsApp send returns)`

**Migration:** `006-announcements.sql`. FKs to `schools(id)`, `users(id)`, `students(id)`. All FK cascades follow the M5 pattern (CASCADE from schools/users/students, CASCADE from announcements → recipients).

Indexes per §3.2:
- `announcement_recipients (announcement_id)` for fanout-status queries
- `announcement_recipients (school_id, parent_user_id)` for "parent inbox" lookup
- partial index `announcement_recipients (announcement_id) WHERE acknowledged_at IS NULL` for "not yet acknowledged"

**Endpoints (all `/api/v1/announcements`):**
- `POST /` — SCHOOL_ADMIN or TEACHER (teacher restricted to CLASS scope where `@perms.teacherTeaches(#classId)`). Body materializes recipients in same tx.
- `GET /` — SCHOOL_ADMIN list (paged, filter by status)
- `GET /{id}` — SCHOOL_ADMIN or original sender
- `POST /{id}/recall` — SCHOOL_ADMIN; sets status RECALLED, fires `announcement.recalled` outbox event
- `GET /{id}/recipients` — SCHOOL_ADMIN; ack tracking view (paged, with deliveryStatus + acknowledgedAt)
- `POST /{id}/acknowledge` — PARENT; sets `acknowledged_at` on the recipient row where parent_user_id matches principal

**Recipient materialization (the interesting part):**
- `scopeType = SCHOOL` → all parents in school with at least one ParentStudentLink. One recipient per (parent, student) pair? Or one per parent? **Open question, see below.**
- `scopeType = GRADE` → all parents whose linked students are enrolled in a class with the matching `gradeLevel`
- `scopeType = CLASS` → all parents whose linked students are enrolled in the class via Enrollment
- `scopeType = CUSTOM` → explicit `recipientStudentIds: List<UUID>` in the request body; service resolves their parents via ParentStudentLink

Strategy: materialize at create time (synchronous), in the same tx as the announcement insert. NFR-P4 says 5k recipients on a single create — should fit in one tx; revisit if profiling says otherwise.

**Outbox event:** `announcement.created` event with payload `{announcementId, schoolId, language, body, attachmentKey, recipientCount}`. M7 consumer picks this up and fans out WhatsApp template sends.

**Permission helpers** to add to `PermissionsHelper`:
- `canSendAnnouncementToScope(AnnouncementScope scope, UUID scopeId)` → SCHOOL_ADMIN always true; TEACHER true only if scope=CLASS and `teacherTeaches(scopeId)`
- `parentReceivedAnnouncement(UUID announcementId)` → true if a row exists in `announcement_recipients` for `(announcementId, currentParentUserId)`

**Mandatory tests (per the project workflow):**
- Cross-tenant isolation suite for each new repo (`AnnouncementRepository`, `AnnouncementRecipientRepository`) — use `UserRepositoryIsolationTest` as template
- **Every new TenantEntity repo MUST override `findById` with `@Query`**
- Integration tests per controller (200/201/403/404/422/409)
- Integration test for SCHOOL-scope fanout: seed 3 parents × 2 students each → expect 6 recipient rows
- Integration test for CLASS-scope fanout filtered by enrollment
- Integration test for parent acknowledgment flow + `parentReceivedAnnouncement` permission
- Outbox event recorded with correct payload (assert via `OutboxRepository`)

**i18n keys** to add (ar + en + default):
- `error.announcement.not_found`
- `error.announcement.invalid_scope` (e.g. CLASS scope without classId, or CUSTOM scope with empty recipient list)
- `error.announcement.already_recalled`
- `error.announcement.not_in_recipients` (parent ack rejected because not a recipient)
- `error.announcement.recipient.not_found`

---

## Open questions to resolve in your mini-plan

The handoff is opinionated where the SRS is clear, but the user owns these decisions — flag them in your mini-plan and wait for answers:

1. **Recipient granularity for SCHOOL/GRADE/CLASS scopes:** one recipient per `(parent, student)` pair, or one per parent (deduped)? Per-pair is more accurate (acknowledgment per child) but creates more rows; per-parent is leaner but loses child context.
2. **GRADE scope encoding:** SchoolClass already has a `gradeLevel: String` field. Pass the string `"Grade 3"` as the scope value, or fix the schema to use a `grade_levels` reference table? Simplest: pass the string and store it in `scopeValue` (rename `scopeId UUID` → `scopeValue VARCHAR(100)` for flexibility across all scope types).
3. **CUSTOM scope storage:** does the announcement need to remember the explicit `studentIds` it was sent to, or is the materialized `announcement_recipients` table the source of truth? (Likely the latter.)
4. **Scheduling (`scheduledFor`):** is M6 in scope for the actual scheduler (a `@Scheduled` job that flips `DRAFT/SCHEDULED → SENDING`), or just persist the field and defer activation to M14 hardening?
5. **Attachments (`attachmentKey`):** persist the field only, or wire up `StorageClient` (S3/MinIO) presign-upload now? Per §1.3 the storage adapter is locked-in but not yet built — recommend persisting the field and deferring storage wiring.

---

## The workflow (this is hard rule, do not skip)

1. **Before writing any code**, post a focused **mini-plan** (5–10 bullets) covering entities, endpoints, events, edge cases, file order — and the open questions above — then **wait for explicit confirmation**.
2. File order per module: Liquibase migration → entity → repo → DTOs + mapper → service → controller → tests → i18n.
3. Pause and let the user run/review at logical breakpoints.
4. After coding, run `mvn test`. Module is not "done" until **all tests are green**, including the new cross-tenant isolation suite.
5. Update `MEMORY.md` only with non-obvious findings worth carrying forward; don't dump what's already derivable from code or the plan.

---

## How to start your first turn

1. Read this file, then the four memory files MEMORY.md links to, then the M6 section of `docs/IMPLEMENTATION_PLAN.md`.
2. Skim `classes/SchoolClassServiceImpl.java` + `classes/ParentStudentLinkRepository.java` — they're the closest pattern to what M6 needs (TenantEntity aggregate + cross-aggregate lookups for fan-out).
3. Skim `common/outbox/OutboxEventRecorder.java` to confirm the outbox API M6 will call.
4. Post the M6 mini-plan with the 5 open questions surfaced. Ask for answers + confirmation.
5. Do NOT start writing code until the user confirms.

That's it. Everything else is in the artifacts.
