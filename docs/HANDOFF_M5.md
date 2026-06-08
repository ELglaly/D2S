# SchoolBridge — Handoff to a Fresh Session (M5)

You are continuing a multi-session SchoolBridge backend build. **M1–M4 are complete and verified green** (38/38 tests pass: `mvn test`). This document is the **only thing you need to read before starting**, plus the files it points at.

---

## Authoritative state (read these first, in order)

1. **`~/.claude/projects/E--D2L/memory/MEMORY.md`** — auto-loaded. Pulls in:
   - `project_schoolbridge.md` — locked-in stack + decisions resolving SRS contradictions (Liquibase not Flyway, Spotless+SpotBugs, etc.)
   - `feedback_schoolbridge_workflow.md` — **strict module-by-module gated cadence the user expects** (mini-plan → confirm → code → mvn test green → next module)
   - `feedback_hibernate_filter_findbyid_bypass.md` — **every TenantEntity repo MUST override findById with a JPQL @Query** (Hibernate's @Filter doesn't apply to Session.find)
   - `feedback_springsecurity_uptoken_trap.md` — never call `setAuthenticated(true)` after the 3-arg `UsernamePasswordAuthenticationToken` constructor

2. **`E:\D2L\docs\IMPLEMENTATION_PLAN.md`** — the master plan. Skim the §10 module roadmap; the M5 slice is your scope.

3. **`E:\D2L\SchoolBridge_SRS_v1.0.md`** — requirements. §3.1 FR-1.2 bulk import + NFR-U1 onboarding-under-4-hours are M5-relevant.

4. **The code at `E:\D2L\src\main\java\com\schoolbridge\api\`** — actual structure. Especially `identity/User.java` and `identity/UserRepository.java` (canonical TenantEntity templates) and `common/tenancy/` (filter + activation aspect).

---

## What's already shipped (M1–M4)

| Module | What's in it |
|---|---|
| M1 skeleton | Maven (Boot 3.3.5, Java 21), Liquibase, Docker/compose, JSON logging, i18n bundles, Testcontainers smoke test |
| M2 common infra | RFC 7807 advice + 8-exception hierarchy + i18n resolver, `BaseEntity` (UUIDv7) + `TenantEntity`, `TenantContext`, AES-256-GCM column converter + blind-index hasher, idempotency filter (Redis), outbox + audit (entities + 002-common-infra.sql), `SecurityConfig` stateless skeleton, `PageResponse`, `RequestIdFilter` |
| M3 tenant | `School` aggregate (embedded `SchoolSettings` w/ jsonb fee offsets), 003-tenant.sql, super-admin `/api/v1/schools` CRUD, audit + outbox + domain events on every state change |
| M4 identity | `User extends TenantEntity` (encrypted name/phone + phone_hash blind index), `PlatformAdmin`, `RefreshToken`, JJWT RS256 (ephemeral dev keypair fallback), parent OTP flow (Redis-backed, logging dispatcher; real WhatsApp send lands in M7), `LoginRateLimiter` (5 fails/15min), `BearerAuthenticationFilter` + `TenantBindingFilter`, `TenantFilterAspect` (activates Hibernate filter on Spring Data repo calls under active tx), `TenantEntityArchUnitTest` build-time guard, mandatory cross-tenant isolation suite for `User` |

The transitional `PlatformAdminTokenFilter` from M3 was deleted in M4 — super-admins now log in via the JWT path.

---

## Your task: Module 5 — Classes, Students, Parent links

Per `docs/IMPLEMENTATION_PLAN.md` §10. Independently demoable.

**Entities (all TenantEntity):**
- `SchoolClass` — `name, gradeLevel, academicYear, homeroomTeacherId (FK→users)`
- `Student` — `fullName (AES-GCM), dateOfBirth, externalId, status`
- `Enrollment` — `studentId, classId` (M:N for tutoring centers where one student attends multiple classes)
- `TeacherAssignment` — `teacherUserId, classId` (drives "teacher sees only their classes" authorization)
- `ParentStudentLink` — `parentUserId, studentId, relationship (MOTHER/FATHER/GUARDIAN), primaryContact (bool)`

**Migration:** `005-classes-students.sql`. FKs to `schools(id)` and `users(id)`. Unique constraints: `(school_id, externalId)` on students, `(student_id, class_id)` on enrollments, `(teacher_user_id, class_id)` on assignments, `(parent_user_id, student_id)` on parent links.

**Endpoints (all `/api/v1`):**
- `POST/GET/PATCH/DELETE /classes` — SCHOOL_ADMIN; teacher list filtered to their assignments
- `POST/GET/PATCH/DELETE /students` — SCHOOL_ADMIN; teacher list filtered to enrolled-class roster; parent list filtered to linked children
- `POST /classes/{id}/enrollments`, `DELETE /enrollments/{id}` — SCHOOL_ADMIN
- `POST /classes/{id}/teachers`, `DELETE /teacher-assignments/{id}` — SCHOOL_ADMIN
- `POST /parent-links`, `DELETE /parent-links/{id}` — SCHOOL_ADMIN
- `POST /students:bulk-import` (multipart CSV; FR-1.2 + NFR-U1) — SCHOOL_ADMIN

**Authorization:** introduce a `PermissionEvaluator` bean (or compact `@PreAuthorize` SpEL helpers) so `@PreAuthorize("@perms.teacherTeaches(#classId)")` and `@perms.parentLinkedTo(#studentId)` work.

**Mandatory tests (per the project workflow):**
- Cross-tenant isolation suite for each new repo (`StudentRepository`, `SchoolClassRepository`, `EnrollmentRepository`, `TeacherAssignmentRepository`, `ParentStudentLinkRepository`) — use `UserRepositoryIsolationTest` as template
- **Every new TenantEntity repo MUST override `findById` with `@Query`** — see `UserRepository`
- Slice tests for each controller (200/201/403/404/422)
- Integration test for CSV bulk import happy path + bad-row rejection

**i18n keys** to add (ar + en + default): `error.class.not_found`, `error.student.not_found`, `error.enrollment.duplicate`, `error.parent_link.duplicate`, `error.bulk_import.row_invalid`.

---

## The workflow (this is hard rule, do not skip)

1. **Before writing any code**, post a focused **mini-plan** (5–10 bullets) covering entities, endpoints, events, edge cases, file order — then **wait for explicit confirmation**.
2. File order per module: Liquibase migration → entity → repo → DTOs + mapper → service → controller → tests → i18n.
3. Pause and let the user run/review at logical breakpoints.
4. After coding, run `mvn test`. Module is not "done" until **all tests are green**, including the new cross-tenant isolation suite.
5. Update `MEMORY.md` only with non-obvious findings worth carrying forward; don't dump what's already derivable from code or the plan.

---

## How to start your first turn

1. Read this file, then the four memory files MEMORY.md links to, then the M5 section of `docs/IMPLEMENTATION_PLAN.md`.
2. Post the M5 mini-plan. Ask for confirmation.
3. Do NOT start writing code until the user confirms.

That's it. Everything else is in the artifacts.
