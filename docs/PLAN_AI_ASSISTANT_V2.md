# AI Assistant Module — Implementation Plan **v2 (Action-Capable)**

**Status:** Planned — build gate after `reporting`, before `hardening`
**Date drafted:** 2026-06-09
**Module path:** `com.schoolbridge.api.assistant`
**Supersedes:** `docs/PLAN_AI_ASSISTANT.md` (v1 was read-only Q&A). v1 remains valid as the
read-tool foundation; v2 adds the **action layer** on top of it.

---

## 0. What changed from v1 → v2

| Aspect | v1 (read-only) | v2 (action-capable) |
|---|---|---|
| Capability | Answer questions from scoped data | Answer questions **and perform every action the caller's role is authorized to do** |
| Tool types | One: read/query tools | Two: **read tools** + **action (mutating) tools** |
| Mutations | None — LLM never writes | Yes — via `ActionTool.preview()` → user confirm → `ActionTool.execute()` |
| Safety gate | N/A | **Confirm-then-execute**: server previews impact, user confirms, only then mutates |
| Roles served | Parent, Teacher | **Parent, Teacher, School Admin** (+ Super Admin noted, deferred) |
| Idempotency | N/A (reads) | Every write carries an idempotency key (reuses `IdempotencyService`) |
| Audit | One row per ask | One row per ask **+ one row per previewed action + one per executed action** |
| Endpoints | `POST /assistant/ask` | `POST /assistant/ask` + `POST /assistant/actions/{token}/confirm` + `POST /assistant/actions/{token}/cancel` |

### The three confirmed design decisions (driving this whole document)

1. **Mirror existing roles — never exceed them.** The assistant exposes *only* what the caller's
   role is already authorized to do. It re-runs the **same** `@PreAuthorize` / `@perms` predicates
   the REST controllers enforce. A teacher gets teacher-actions; a parent gets parent-actions; an
   admin gets admin-actions. There is **no new grant of power** — "add user" appears only for the
   role that can already do it (Super Admin), "add student / create class / enroll" only for
   School Admin.
2. **Cover all roles end-to-end.** Parent + Teacher + School Admin get a complete read+action
   catalog. Super Admin (platform/cross-tenant) is documented but deferred (different principal
   type, no single tenant context — see §13).
3. **Confirm-then-execute for every mutation.** The model can *propose* an action, but the server
   computes a human-readable impact preview and **halts**. The mutation runs only after an explicit
   user confirmation against a signed, single-use token. The LLM is never in the mutation path.

---

## 1. Overview

A natural-language assistant (`POST /api/v1/assistant/ask`) where parents, teachers, and school
admins send free-text requests in Arabic or English. The assistant:

- **Answers** scoped data questions (v1 behaviour), and
- **Acts** on the caller's behalf — marking attendance, creating/grading homework, posting
  announcements, adding students, enrolling, linking parents, etc. — **strictly within the caller's
  existing role permissions**, and **only after an explicit confirmation** for any mutation.

Claude (Anthropic Java SDK) interprets the request, selects typed **tools** (thin Java adapters
over existing services), and the server executes them **as the authenticated caller**, under the
caller's tenant + role scope. The model composes a localized answer or a confirmation prompt.

> **The LLM never touches the database, never sees a DB handle, and never decides authorization or
> confirmation.** It only sees the tool catalog its role is allowed, and structured tool results.
> Scope checks, the confirmation gate, idempotency, and audit are all deterministic server code.

### Why this is safe by construction

The assistant runs **inside the caller's authenticated HTTP request**, so the Spring
`SecurityContext` already holds the caller's `StaffPrincipal` / `ParentPrincipal`. Every tool:

1. calls the **same** `PermissionsHelper` (`@perms`) predicate the matching controller uses, then
2. calls the **same** service method the controller calls, passing the **same** principal id.

Therefore the assistant inherits — unchanged — the existing tenant `@Filter`, audit, outbox, and
business rules. The assistant cannot do anything a determined user couldn't already do via the REST
API; it only makes it conversational.

> ⚠️ **Critical implementation note:** `@PreAuthorize` is a *controller* method interceptor. When a
> tool calls a **service** directly, `@PreAuthorize` does **not** fire. Tools **must** explicitly
> invoke the corresponding `@perms` predicate (e.g. `perms.teacherTeaches(classId)`) before calling
> the service. This is the single most important security rule in the module.

---

## 2. Confirmed Decisions

| Decision | Choice |
|---|---|
| Permission model | **Mirror existing roles**; assistant re-checks the exact `@PreAuthorize`/`@perms` rules. No new grants. |
| Roles covered | Parent, Teacher, School Admin (full). Super Admin deferred (§13). |
| Write execution | **Confirm-then-execute**; server previews impact, user confirms a single-use token, then mutate. |
| Module placement | After all planned modules: `… → reporting → assistant → hardening` |
| Mutations | Go through existing services with the caller's principal — no bypass, no new write paths. |
| Idempotency | Every executed action uses `IdempotencyService` keyed on the confirmation token. |
| DB migration | One small table for the durable action ledger (`assistant_action`) — optional but recommended (§12). Pending tokens live in Redis. |
| PII to Anthropic | First names + data, same as v1 (confirmed). Never national IDs / encrypted attrs / secrets. |

---

## 3. Full Gated Build Order (unchanged)

```
tenant → identity → classes → announcements → integrations
→ attendance → homework (M9)
→ fees → messaging → reporting
→ assistant      ← this module (v2)
→ hardening
```

---

## 4. Role → Capability Model (the heart of v2)

This is the authoritative mapping of **what each role can do via the assistant**, derived directly
from the controllers' `@PreAuthorize` annotations and `PermissionsHelper`. The assistant exposes a
tool **only** to a role that already passes the controller's guard.

Legend — **R** = read tool, **A** = action (mutating) tool (requires confirm).
Scope guard = the `@perms` predicate / service-layer check re-run inside the tool.

### 4.1 PARENT

| Capability | Type | Backing service | Scope guard (re-checked in tool) |
|---|---|---|---|
| List my children | R | `ParentChildrenService.listChildren(parentUserId)` | self (principal) |
| Child attendance (today / range / absence count) | R | `AttendanceService.history(studentId, from, to)` | `perms.parentLinkedTo(studentId)` |
| Child homework feed / due items | R | `HomeworkService.parentFeed(parentUserId, childId, page)` | service: linked-child (anti-enumeration 404) |
| Child grades / latest grade | R | `GradeService.listByStudent(studentId)` | `perms.parentLinkedTo(studentId)` |
| Single homework / grade item | R | `HomeworkService.findByIdForParent(id, parentUserId)` / `GradeService.findById(id)` | service: recipient/link check |
| Unacknowledged announcements | R | `AnnouncementRecipientRepository` (unack rows) | `parentUserId`-scoped query |
| **Respond to an absence alert** | **A** | `AttendanceService.recordParentResponse(attId, parentUserId, req)` | service: parent linked to record (404 if not) |
| **Acknowledge a homework item** | **A** | `HomeworkService.acknowledge(id, parentUserId)` | service: recipient row (404 if not) |
| **Acknowledge an announcement** | **A** | `AnnouncementService.acknowledge(id, parentUserId)` | `perms.parentReceivedAnnouncement(id)` |

> Parents have **no** create/update/delete of users, students, classes, grades, attendance, or
> homework. Their only mutations are the three acknowledgement/response flows above. This exactly
> mirrors `AttendanceController#parentResponse`, `HomeworkController#acknowledge`,
> `AnnouncementController#acknowledge`.

### 4.2 TEACHER

| Capability | Type | Backing service | Scope guard (re-checked in tool) |
|---|---|---|---|
| List my classes | R | `SchoolClassService.listMyClasses(teacherUserId)` | self (principal) |
| Class roster + attendance for a date | R | `AttendanceService.roster(classId, date)` | `perms.teacherTeaches(classId)` |
| Student attendance history | R | `AttendanceService.history(studentId, from, to)` | role TEACHER (controller allows) |
| Class enrollments | R | `EnrollmentService.listByClass(classId)` | `perms.teacherTeaches(classId)` |
| Class grades | R | `GradeService.listByClass(classId, page)` | `perms.teacherTeaches(classId)` |
| Student grades | R | `GradeService.listByStudent(studentId)` | role TEACHER (controller allows) |
| Homework list / item / recipients | R | `HomeworkService.list/findById/listRecipients` | `perms.teacherTeaches(classId)` / `perms.isHomeworkAuthor(id)` |
| Class subjects | R | `ClassSubjectService.listByClass(classId)` | role/teacher (per controller) |
| **Mark a student's attendance** | **A** | `AttendanceService.mark(staffId, MarkAttendanceRequest)` | `perms.teacherTeaches(request.classId())` |
| **Mark all present (bulk)** | **A** | `AttendanceService.markAllPresent(staffId, MarkAllPresentRequest)` | `perms.teacherTeaches(request.classId())` |
| **Create a grade** | **A** | `GradeService.create(schoolId, actorId, CreateGradeRequest)` | `perms.teacherTeaches(request.classId())` |
| **Update a grade** | **A** | `GradeService.update(id, actorId, UpdateGradeRequest)` | role TEACHER (+ service-layer ownership; see §11.4) |
| **Create homework (draft or published)** | **A** | `HomeworkService.create(schoolId, actorId, CreateHomeworkRequest)` | `perms.teacherTeaches(request.classId())` |
| **Publish homework** (fans out to parents) | **A** | `HomeworkService.publish(id)` | `perms.isHomeworkAuthor(id)` |
| **Update homework** | **A** | `HomeworkService.update(id, UpdateHomeworkRequest)` | `perms.isHomeworkAuthor(id)` |
| **Archive homework** (soft delete) | **A** | `HomeworkService.archive(id)` | `perms.isHomeworkAuthor(id)` |
| **Post a CLASS announcement** (fans out) | **A** | `AnnouncementService.create(schoolId, senderId, CreateAnnouncementRequest)` | `perms.canSendAnnouncementToScope(CLASS, classId)` |

> Teachers **cannot** add users/students, create classes, enroll students, link parents, manage
> subjects, recall announcements, or delete grades — those are admin-only and the assistant will say
> so (and offer to draft a request to an admin). This mirrors every controller guard above.

### 4.3 SCHOOL_ADMIN

Inherits **all teacher read+action capabilities school-wide** (no `teacherTeaches` narrowing — admin
passes the `hasRole('SCHOOL_ADMIN')` branch), **plus**:

| Capability | Type | Backing service | Notes |
|---|---|---|---|
| **Add student** | **A** | `StudentService.create(schoolId, CreateStudentRequest)` | high-value confirm |
| Update student | A | `StudentService.update(id, UpdateStudentRequest)` | |
| **Delete student** | A | `StudentService.delete(id)` | **destructive** — cascades enrollments + parent links |
| Bulk import students (CSV) | A* | `StudentService.bulkImport(schoolId, stream)` | *needs file upload — assistant guides, execution via UI handoff (§9.4)* |
| List / get students | R | `StudentService.list/findById` | |
| **Create class** | A | `SchoolClassService.create(schoolId, CreateSchoolClassRequest)` | |
| Update class | A | `SchoolClassService.update(id, UpdateSchoolClassRequest)` | |
| **Delete class** | A | `SchoolClassService.delete(id)` | **destructive** — cascades enrollments + assignments |
| List classes / by teacher | R | `SchoolClassService.list/listByHomeroomTeacher` | |
| **Enroll student in class** | A | `EnrollmentService.enroll(classId, EnrollStudentRequest)` | |
| Remove enrollment | A | `EnrollmentService.delete(id)` | |
| **Link parent to student** | A | `ParentStudentLinkService.create(schoolId, CreateParentLinkRequest)` | |
| Remove parent-student link | A | `ParentStudentLinkService.delete(id)` | |
| List parent links for a student | R | `ParentStudentLinkService.listByStudent(studentId)` | |
| Create / update / delete subject | A | `SubjectService.create/update/delete` | delete destructive |
| Add / remove subject on a class | A | `ClassSubjectService.add/remove` | |
| Assign / unassign teacher to subject | A | `TeacherSubjectAssignmentService.assign/remove` | |
| **Post announcement (any scope: SCHOOL/GRADE/CLASS/CUSTOM)** | A | `AnnouncementService.create(...)` | fanout — confirm |
| **Recall announcement** | A | `AnnouncementService.recall(id)` | |
| Announcement recipients / delivery status | R | `AnnouncementService.listRecipients(id, page)` | |
| **Delete a grade** | A | `GradeService.delete(id)` | **destructive** |

### 4.4 SUPER_ADMIN (platform admin — **deferred**, documented for completeness)

This is where **"add user"** actually lives (`UserController` → `hasAnyRole('SUPER_ADMIN')`).

| Capability | Type | Backing service |
|---|---|---|
| **Add user** (staff/parent) in a school | A | `UserService.create(schoolId, CreateUserRequest)` |
| List / get users in a school | R | `UserService.list/findById` |
| Create / suspend / reactivate school, edit settings | A | `SchoolService.*` |

> **Deferred** because `SUPER_ADMIN` is a cross-tenant `PlatformAdmin` principal — there is no single
> `TenantContext` for the request, and several scope assumptions in §11 (tenant resolved from
> context) don't hold. Supporting super-admin requires an explicit `schoolId` argument on every tool
> and a separate registry. Tracked as a Phase-5 follow-up; not in the v2 MVP. **This is the formal
> answer to "add user via assistant": it is a Super-Admin capability and is intentionally out of the
> parent/teacher/school-admin MVP.**

---

## 5. Architecture (v2 additions over v1)

```
com.schoolbridge.api.assistant/
  AssistantController.java            # POST /ask (SSE), POST /actions/{token}/confirm, /cancel
  AssistantService.java              # interface
  AssistantServiceImpl.java          # orchestration loop + confirmation gate

  dto/
    AskRequest.java                  # { question, language? }
    ConfirmActionRequest.java        # { token, decision: CONFIRM|CANCEL }  (REST + chat both supported)
    AssistantAnswer.java             # final answer + metadata
    AssistantChunk.java              # SSE envelope: delta | toolStatus | confirmRequired | done | error
    ActionPreview.java               # { token, summaryAr, summaryEn, impact{}, expiresAt }

  llm/
    AnthropicClientConfig.java       # SDK client bean + properties (gated on enabled)
    AssistantProperties.java         # @ConfigurationProperties("schoolbridge.assistant")
    LlmGateway.java                  # interface (decouples SDK for tests)
    AnthropicLlmGateway.java         # SDK impl; model claude-haiku-4-5-20251001 (configurable)
    SystemPrompt.java                # role, language, guardrails, "always confirm before acting"

  tools/
    Tool.java                        # marker: name(), description(), jsonSchema(), kind()
    ReadTool.java                    # extends Tool: execute(args, ctx) -> ToolResult
    ActionTool.java                  # extends Tool: preview(args, ctx) -> ActionPreview;
                                     #               execute(token, ctx) -> ToolResult
    ToolRegistry.java                # registers ONLY tools whose role-guard the principal passes
    ToolContext.java                 # record(schoolId, principal, role, language, idempotencyKey)
    ToolResult.java                  # typed JSON result fed back to the model (ok | denied | clarify | error)
    read/                            # all read tools (v1 catalog, extended) — §4 R rows
    action/                          # all action tools — §4 A rows
      ...AttendanceMarkTool, GradeCreateTool, HomeworkPublishTool, StudentCreateTool, etc.

  confirm/
    PendingAction.java               # record persisted to Redis: token, userId, schoolId, toolName,
                                     #   resolvedArgs, impact, createdAt, expiresAt, single-use flag
    PendingActionStore.java          # Redis put/get/consume (single-use), 5-min TTL
    ConfirmationTokenService.java    # generate opaque token (UUID), HMAC-sign, validate, bind to user

  cache/
    AssistantCache.java              # Redis read-answer cache (READ asks only; never cache actions)

  audit/
    AssistantAuditRecorder.java      # assistant.ask / .action.preview / .action.execute / .action.cancel
```

### Two-phase action contract

```java
public interface ActionTool extends Tool {
  /** Validates scope + resolves names→IDs + computes human impact. NEVER mutates.
   *  Stores a single-use PendingAction in Redis and returns a token + bilingual summary. */
  ActionPreview preview(JsonNode args, ToolContext ctx);

  /** Loads & consumes the PendingAction by token, RE-validates scope, then mutates via the
   *  existing service using ctx.principal + ctx.idempotencyKey. Returns a typed ToolResult. */
  ToolResult execute(String token, ToolContext ctx);
}
```

Read tools keep the v1 single-method shape (`execute(args, ctx)`).

---

## 6. The Confirm-Then-Execute Protocol (detailed)

```
PHASE A — propose & preview (single /ask request)
1. /ask: validate, resolve principal+role+language, build role-scoped ToolRegistry.
2. Tool-use loop (max iters = 4):
   - Model returns a READ tool_use  → execute → append tool_result → continue loop.
   - Model returns an ACTION tool_use:
       a. Orchestrator calls tool.preview(args, ctx)  (NO mutation).
       b. preview() re-runs the @perms guard:
            - denied → ToolResult.denied(...) fed back to model; model explains, loop continues.
            - ambiguous name (e.g. two "Ahmed") → ToolResult.clarify(...); model asks; loop continues.
            - ok → build PendingAction{token,...}, store in Redis (TTL 5m, single-use), return ActionPreview.
       c. Orchestrator STOPS the loop, emits SSE `confirmRequired` with the bilingual summary +
          token + structured impact, audits `assistant.action.preview`, completes the emitter.
   - Model returns final text (no tool) → stream `delta` chunks → `done`. (Pure read answer.)

PHASE B — confirm (separate request)
3. Client confirms via EITHER:
     - REST: POST /assistant/actions/{token}/confirm   (Flutter "Confirm" button), or
     - Chat: POST /assistant/ask  with a yes/no message + the token in context. A deterministic
       intent classifier (not the LLM) maps "yes/نعم/أكد/confirm" → CONFIRM, "no/لا/إلغاء" → CANCEL.
4. /confirm: load PendingAction by token (must exist, not expired, belong to caller, unconsumed).
     - CANCEL → consume token, audit `assistant.action.cancel`, return localized "cancelled".
     - CONFIRM →
         a. derive idempotencyKey = "assistant:action:" + token (stable, single-use).
         b. tool.execute(token, ctx): consume token (atomic), RE-validate @perms guard,
            call the service with ctx.principal + idempotencyKey.
         c. stream the localized success result; audit `assistant.action.execute` (HashMap metadata).
5. Token is single-use and 5-min TTL: replay, expiry, or wrong-user → localized error, no mutation.
```

**Why a server-side token, not "the model said the user confirmed":** the confirmation decision is
security-critical and must be deterministic and replay-safe. The LLM proposes; the **server** owns
the gate. The model literally cannot execute — `ActionTool.execute` is only reachable through
`/confirm` with a valid Redis-backed token.

---

## 7. Orchestration Flow (read + action unified)

```
1. Validate AskRequest (non-blank, max 500 chars).
2. Resolve principal + role + language (detect from text; fall back to school default Language).
3. READ-cache check (read asks only — keyed {userId, normalizedQuestion, day}):
     HIT  → stream cached answer → audit assistant.ask.cache_hit → return.
     MISS → continue.   (Action proposals are NEVER cached.)
4. Build ToolRegistry for the principal's role (parent | teacher | school_admin tool sets).
5. Tool-use loop (max 4):  READ → execute;  ACTION → preview + halt (see §6 Phase A).
6. If a confirmRequired was emitted → stop here (await Phase B).
   Else cache the read answer (TTL = end of day) and finish.
7. Audit every step with HashMap metadata
   (question hash, language, tools invoked, iterations, token usage, latencyMs, cached flag).
   MUST use HashMap — never Map.of() (outbox/audit NPE gotcha).
```

---

## 8. Complete Tool Catalog (per role)

> Naming: snake_case, verb-first for actions. `confirm = yes` ⇒ goes through Phase A/B.
> Every tool resolves `schoolId` from `TenantContext.require()` — never from LLM args.

### 8.1 Parent tools

| Tool | Kind | Args (LLM-supplied) | Backing call | Confirm |
|---|---|---|---|---|
| `list_my_children` | R | — | `ParentChildrenService.listChildren` | — |
| `get_child_attendance` | R | `childName?`, `from?`, `to?` | `AttendanceService.history` | — |
| `get_child_absence_count` | R | `childName?`, `month?` | `AttendanceService.history` (count ABSENT) | — |
| `get_child_homework` | R | `childName?`, `dueOn?` | `HomeworkService.parentFeed` | — |
| `get_child_grades` | R | `childName?`, `subject?` | `GradeService.listByStudent` | — |
| `get_unacknowledged_announcements` | R | — | `AnnouncementRecipientRepository` | — |
| `respond_to_absence_alert` | A | `childName`, `date`, `reason`/`status` | `AttendanceService.recordParentResponse` | **yes** |
| `acknowledge_homework` | A | `homeworkRef` (resolves to recipient row) | `HomeworkService.acknowledge` | **yes (light)** |
| `acknowledge_announcement` | A | `announcementRef` | `AnnouncementService.acknowledge` | **yes (light)** |

### 8.2 Teacher tools

| Tool | Kind | Args | Backing call | Confirm |
|---|---|---|---|---|
| `list_my_classes` | R | — | `SchoolClassService.listMyClasses` | — |
| `get_class_attendance` | R | `classRef`, `date?` | `AttendanceService.roster` | — |
| `get_student_attendance` | R | `studentRef`, `from?`, `to?` | `AttendanceService.history` | — |
| `get_class_enrollments` | R | `classRef` | `EnrollmentService.listByClass` | — |
| `get_class_grades` | R | `classRef` | `GradeService.listByClass` | — |
| `get_student_grades` | R | `studentRef` | `GradeService.listByStudent` | — |
| `list_homework` | R | `classRef?`, `status?`, `dueRange?` | `HomeworkService.list` | — |
| `get_homework_recipients` | R | `homeworkRef` | `HomeworkService.listRecipients` | — |
| `mark_attendance` | A | `classRef`, `studentRef`, `date`, `status` | `AttendanceService.mark` | **yes** |
| `mark_all_present` | A | `classRef`, `date` | `AttendanceService.markAllPresent` | **yes (bulk)** |
| `create_grade` | A | `classRef`, `studentRef`, `subject?`, `assessment`, `score` | `GradeService.create` | **yes** |
| `update_grade` | A | `gradeRef`, fields | `GradeService.update` | **yes** |
| `create_homework` | A | `classRef`, `subject`, `description`, `dueDate`, `publish?` | `HomeworkService.create` | **yes** |
| `publish_homework` | A | `homeworkRef` | `HomeworkService.publish` | **yes (fanout)** |
| `update_homework` | A | `homeworkRef`, fields | `HomeworkService.update` | **yes** |
| `archive_homework` | A | `homeworkRef` | `HomeworkService.archive` | **yes** |
| `post_class_announcement` | A | `classRef`, `title`, `body`, `requiresAck?` | `AnnouncementService.create` (scope=CLASS) | **yes (fanout)** |

### 8.3 School-admin tools

All teacher tools (school-wide, no `teacherTeaches` narrowing) **plus**:

| Tool | Kind | Args | Backing call | Confirm |
|---|---|---|---|---|
| `add_student` | A | `fullName`, `dateOfBirth?`, `externalId?` | `StudentService.create` | **yes** |
| `update_student` | A | `studentRef`, fields | `StudentService.update` | **yes** |
| `delete_student` | A | `studentRef` | `StudentService.delete` | **yes (destructive)** |
| `create_class` | A | `name`, `grade?`, `homeroomTeacherRef?` | `SchoolClassService.create` | **yes** |
| `update_class` | A | `classRef`, fields | `SchoolClassService.update` | **yes** |
| `delete_class` | A | `classRef` | `SchoolClassService.delete` | **yes (destructive)** |
| `enroll_student` | A | `classRef`, `studentRef` | `EnrollmentService.enroll` | **yes** |
| `remove_enrollment` | A | `enrollmentRef` (or class+student) | `EnrollmentService.delete` | **yes** |
| `link_parent_to_student` | A | `parentRef`, `studentRef`, `relationship`, `primary?` | `ParentStudentLinkService.create` | **yes** |
| `remove_parent_link` | A | `linkRef` (or parent+student) | `ParentStudentLinkService.delete` | **yes** |
| `create_subject` / `update_subject` / `delete_subject` | A | … | `SubjectService.*` | **yes** |
| `add_subject_to_class` / `remove_class_subject` | A | `classRef`, `subjectRef` | `ClassSubjectService.*` | **yes** |
| `assign_teacher_to_subject` / `remove_teacher_assignment` | A | `classRef`, `subjectRef`, `teacherRef` | `TeacherSubjectAssignmentService.*` | **yes** |
| `post_announcement` | A | `scope (SCHOOL/GRADE/CLASS/CUSTOM)`, targets, `title`, `body` | `AnnouncementService.create` | **yes (fanout)** |
| `recall_announcement` | A | `announcementRef` | `AnnouncementService.recall` | **yes** |
| `get_announcement_recipients` | R | `announcementRef` | `AnnouncementService.listRecipients` | — |
| `delete_grade` | A | `gradeRef` | `GradeService.delete` | **yes (destructive)** |
| `bulk_import_students` | A* | CSV file | `StudentService.bulkImport` | **UI handoff (§9.4)** |

### 8.4 Name → ID resolution (`...Ref` args)

The LLM is given **names** (child name, class name, teacher name, subject name, homework subject),
never raw UUIDs. Each tool resolves the name to an entity **in Java**, scoped to the tenant + the
caller's visible set:

- 0 matches → `ToolResult.clarify("I couldn't find a student named …")`.
- 1 match → proceed.
- >1 match → `ToolResult.clarify` listing candidates ("Ahmed Ali in 5A, or Ahmed Saleh in 6B?").

This keeps UUIDs out of the model entirely and prevents IDOR via model-supplied ids.

---

## 9. Endpoints, Streaming, File Handling

### 9.1 Routes (slash-style verbs — never `:verb`; clients percent-encode `:` → 404)

```
POST /api/v1/assistant/ask                      # SSE; read answer OR confirmRequired
POST /api/v1/assistant/actions/{token}/confirm  # execute the pending action (idempotent)
POST /api/v1/assistant/actions/{token}/cancel   # discard the pending action
```

Register all three as **authenticated** in `SecurityConfig`.

### 9.2 SSE & ResponseBodyAdvice

`/ask` returns `SseEmitter` (`text/event-stream`). `ApiResponseBodyAdvice.supports()` only triggers
for `MappingJackson2HttpMessageConverter` **and** the `com.schoolbridge.api` package, so SSE frames
are **not** wrapped. `/confirm` and `/cancel` return a normal JSON `ApiResponse` (wrapped as usual).

`confirmRequired` SSE chunk shape:
```json
{ "type": "confirmRequired",
  "token": "a1b2…",
  "summary": "سأسجّل حضور ٢٤ طالبًا في الصف ٥أ ليوم الأحد ٢٠٢٦-٠٦-٠٧. أؤكّد؟",
  "summaryEn": "I'll mark 24 students present in Class 5A for Sun 2026-06-07. Confirm?",
  "impact": { "action": "mark_all_present", "classId": "…", "studentCount": 24, "date": "2026-06-07" },
  "expiresAt": "2026-06-09T08:25:00Z" }
```

### 9.3 Mid-stream errors

Emit a terminal SSE `error` chunk + complete the emitter; log full context server-side. The global
`@RestControllerAdvice` cannot intercept after headers flush.

### 9.4 File-based actions (bulk import)

`bulk_import_students` needs a multipart CSV — not expressible in a chat string. The assistant
**guides** ("send me the CSV with columns externalId,fullName,dateOfBirth,className") and emits a
`confirmRequired` whose impact instructs the Flutter client to open the existing upload screen
(`POST /students:bulk-import`). The assistant does **not** stream file bytes through the LLM.

---

## 10. Configuration

```yaml
schoolbridge:
  assistant:
    enabled: ${ASSISTANT_ENABLED:false}        # ships dark
    api-key: ${ANTHROPIC_API_KEY:}             # env-only; validated at startup when enabled=true
    model: claude-haiku-4-5-20251001           # configurable; escalate to Sonnet for admin multi-step
    max-tool-iterations: 4
    request-timeout: 30s
    read-cache-ttl: PT24H
    max-question-length: 500
    actions:
      enabled: ${ASSISTANT_ACTIONS_ENABLED:false}   # second kill-switch: reads can ship before writes
      confirmation-ttl: PT5M                          # pending-action token lifetime
      destructive-require-typed-confirm: true         # delete_* / recall need explicit "yes"/"نعم", not a tap
      max-bulk-impact: 200                            # refuse single-action impact above N rows; suggest UI
```

**Security:** `ANTHROPIC_API_KEY` is env-only, never in `application.yml` defaults, never logged,
never in audit metadata; validated present at startup when `enabled=true`. The
`actions.enabled` flag lets reads (v1 parity) go live before writes are switched on.

### pom.xml dependency
```xml
<dependency>
  <groupId>com.anthropic</groupId>
  <artifactId>anthropic-java</artifactId>
  <version>${anthropic.version}</version>
</dependency>
```
Pin `<anthropic.version>` to a fixed release. If the SDK's HTTP client aborts on Windows JDK,
configure it explicitly (mirrors the `SimpleClientHttpRequestFactory` gotcha for RestClient).

---

## 11. Security Model (deep)

### 11.1 Authorization parity (the core invariant)
Every tool maps 1:1 to a controller action and **re-runs that controller's guard** before touching a
service. The mapping table in the **Appendix** is the test oracle: a tool must be reachable by a role
**iff** the corresponding endpoint is. CI test asserts each action tool calls its `@perms` predicate.

### 11.2 schoolId & IDs never come from the LLM
`schoolId` is always `TenantContext.require()`. Entity references are **names** resolved server-side
to tenant-scoped ids. The model cannot supply a UUID to reach another tenant's row.

### 11.3 Confirmation token integrity
Opaque UUID, HMAC-signed, bound to `{userId, toolName, resolvedArgsHash}`, single-use, 5-min TTL in
Redis. `/confirm` rejects expired/replayed/wrong-user tokens with a localized error and zero
mutation. Destructive actions (`delete_*`, `recall_*`, `delete_grade`) require a typed yes/نعم, not a
bare tap, when `destructive-require-typed-confirm=true`.

### 11.4 Service-layer ownership gaps
A few controllers guard only by **role** and rely on the **service** for fine-grained ownership
(e.g. `GradesController#update` is `hasRole('TEACHER')`; the service is expected to confirm the
teacher teaches that grade's class). Action tools **must not** assume the coarse guard is enough —
they re-derive the class from the entity and call `perms.teacherTeaches(classId)` before mutating.
Document each such case inline; add a focused cross-teacher test.

### 11.5 Prompt injection & multi-action requests
The LLM has no DB access and cannot self-confirm. Even a fully injected prompt can at most *propose*
an action the caller is already allowed to take, which still requires the human confirm gate. For
"do X and Y and Z" requests, the assistant previews and confirms **one action at a time** (no batch
auto-execute) unless a future explicit "batch confirm" is designed.

### 11.6 Idempotency
`/confirm` derives a stable `idempotencyKey` from the token and passes it to the service via
`IdempotencyService`, so a double-tapped confirm or client retry never double-creates.

### 11.7 Secrets & PII
API key env-only, excluded from logs/audit. PII to Anthropic limited to first names + the data
needed for natural answers; never national IDs, AES-encrypted attributes, phone numbers, or secrets.
Confirm DPA before prod.

---

## 12. Data Model / Migration

Pending tokens are ephemeral → **Redis** (no migration). For a durable, queryable record of what the
assistant *did* (beyond `audit_logs`), add one optional forward-only Liquibase migration:

```
db/changelog/0xx-assistant-action.sql   (Liquibase only; forward-only)

assistant_action(
  id              uuid pk,
  school_id       uuid not null references schools(id) ON DELETE CASCADE,
  actor_user_id   uuid not null references users(id)  ON DELETE CASCADE,   -- FK cascade gotcha
  role            text not null,
  tool_name       text not null,
  status          text not null,         -- PREVIEWED | CONFIRMED | EXECUTED | CANCELLED | EXPIRED | FAILED
  impact_json     jsonb,                 -- resolved args + computed impact (no secrets)
  result_ref      uuid,                  -- created/affected entity id when applicable
  idempotency_key text,
  created_at      timestamptz not null default now(),
  executed_at     timestamptz
)
```

> FK refs to `users(id)` / `schools(id)` **must** be `ON DELETE CASCADE` (test-setup `deleteAll()`
> gotcha). If product doesn't need this table for v2, `audit_logs` alone suffices — keep it optional.

---

## 13. Implementation Phases

### Phase 1 — Read tools (v1 parity, all three roles)
1. `AssistantProperties` + config; `Tool`/`ReadTool`/`ToolContext`/`ToolResult`/`ToolRegistry`.
2. All **read** tools (§8 R rows) wrapping existing services + `@perms` guards + name resolution.
3. Unit tests per read tool: happy / denied cross-scope / ambiguous name. Role-filtered registry.

### Phase 2 — LLM gateway & read orchestration
4. Anthropic dep + `AnthropicClientConfig` (gated on `enabled`); `LlmGateway` + `AnthropicLlmGateway`.
5. `SystemPrompt` (role, language, **"never act without a confirmed token"** guardrail).
6. `AssistantServiceImpl` read loop; tests with stubbed gateway (single/multi-tool, max-iter, denied).

### Phase 3 — Action layer (preview / confirm / execute)
7. `ActionTool` contract; `PendingAction` + `PendingActionStore` (Redis) + `ConfirmationTokenService`.
8. All **action** tools (§8 A rows): `preview()` (scope + resolve + impact) and `execute()` (re-guard
   + service call + idempotency). Reuse `IdempotencyService`.
9. Orchestrator confirmation gate: ACTION tool_use → preview + halt + `confirmRequired` SSE.
10. `/actions/{token}/confirm` + `/cancel` endpoints; deterministic yes/no intent classifier.
11. Per-tool tests: preview-no-mutation, denied, ambiguous, confirm→execute, replay/expiry/wrong-user,
    destructive typed-confirm, idempotent double-confirm, `max-bulk-impact` refusal.

### Phase 4 — Endpoint, cache, audit, hardening
12. `AssistantController` (SSE `/ask`); read-answer cache (reads only); `AssistantAuditRecorder`
    (`ask` / `action.preview` / `action.execute` / `action.cancel`, HashMap metadata).
13. i18n ar+en for all fixed strings (confirm prompts, "no access", "couldn't find that child",
    "cancelled", "this needs an admin").
14. Per-user rate limiting (reuse `LoginRateLimiter` Redis pattern); Resilience4j circuit breaker +
    timeout around Anthropic; Micrometer (asks, cache-hit ratio, tokens, tool distribution,
    preview/confirm/execute counts, confirm-abandon rate, latency).
15. Optional `assistant_action` migration (§12) if product wants the durable ledger.
16. Integration tests: SSE not double-wrapped, cache-hit short-circuit, **two-school tenant
    isolation**, parent-vs-teacher-vs-admin tool availability, full confirm→execute→audit happy path,
    rate-limit 429.

### Phase 5 — Super Admin (deferred)
17. Cross-tenant principal handling, explicit `schoolId` arg per tool, `add_user` + school management.

Phases 1–4 ship behind `enabled=false`; actions additionally behind `actions.enabled=false`.

---

## 14. Example Conversations

**Teacher — bulk action with confirm**
```
T: علّم كل طلاب صف ٥أ حاضرين النهاردة
A: (preview) سأسجّل حضور ٢٤ طالبًا في الصف ٥أ ليوم ٢٠٢٦-٠٦-٠٩. أؤكّد؟   [تأكيد] [إلغاء]
T: تأكيد
A: ✅ تم تسجيل ٢٤ حاضرًا، وتم تخطّي طالبَين لهما سجل مسبق.
```

**Teacher — create + publish homework (fanout)**
```
T: Add math homework for 6B, page 40 problems, due Thursday, and publish it.
A: (preview) I'll create AND publish "Math — page 40 problems" for 6B, due 2026-06-12. Publishing
   notifies 19 linked parents. Confirm?   [Confirm] [Cancel]
T: yes
A: ✅ Created and published. 19 parents notified.
```

**Parent — respond to absence alert**
```
P: ابني كان غايب امبارح لأنه كان عيان
A: (preview) سأسجّل أن غياب "أحمد" يوم ٢٠٢٦-٠٦-٠٨ بسبب: مرض. أؤكّد؟   [تأكيد] [إلغاء]
P: نعم
A: ✅ تم إرسال ردّك للمدرسة.
```

**School admin — add + enroll (two confirmed steps)**
```
S: Add a new student Sara Mostafa and put her in class 4A.
A: (preview 1) I'll create student "Sara Mostafa". Confirm?   [Confirm]
S: confirm
A: ✅ Created. Next: enroll Sara Mostafa in 4A — confirm?   [Confirm]
S: confirm
A: ✅ Sara Mostafa enrolled in 4A.
```

**Teacher hitting an admin-only wall (mirror-roles in action)**
```
T: Add a new student to my class.
A: Adding students is a school-admin action — I can't do it from a teacher account. Want me to
   draft a request to your school admin, or list who can do it?
```

**Parent hitting a wall**
```
P: Change my son's grade in math.
A: Grades can only be set by teachers or admins, so I can't change that for you. I can show you the
   current grade or help you message the teacher.
```

---

## 15. Risk Assessment

| Risk | Severity | Mitigation |
|---|---|---|
| Unauthorized mutation (privilege escalation) | **CRITICAL** | Tools re-run the exact `@perms` guard; service-layer ownership re-checked (§11.4); Appendix mapping is the CI oracle; cross-role + cross-tenant tests mandatory |
| Accidental/irreversible write from misread intent | **CRITICAL** | Confirm-then-execute on **every** action; bilingual impact preview; typed confirm for destructive; `max-bulk-impact` cap |
| Confirmation-token replay / forgery | High | HMAC-signed, single-use, user-bound, 5-min TTL Redis token; consumed atomically on execute |
| Scope leakage via model-supplied IDs | High | `schoolId` from `TenantContext`; names resolved server-side; no UUIDs to the model |
| Prompt injection | High | LLM cannot self-confirm or reach the DB; worst case = proposing an already-permitted action behind the human gate |
| Double execution on retry | Medium | `IdempotencyService` keyed on the token |
| PII to Anthropic | High | Minimal fields, first names only; no national IDs/secrets; DPA before prod |
| Anthropic SDK HTTP on Windows JDK | Medium | Smoke-test early; configure SDK HTTP client (mirror `SimpleClientHttpRequestFactory`) |
| Cost/latency runaway | Medium | Haiku + iteration cap + read cache + rate limit + circuit breaker |
| SSE mid-stream errors | Medium | Terminal `error` chunk + emitter completion + server log |
| Secret exposure | Low | API key env-only, startup-validated, excluded from logs/audit |

---

## 16. Test Strategy

| Layer | Coverage |
|---|---|
| Unit (read) | Each read tool: happy / denied / ambiguous; registry role filtering; system-prompt language; cache-key normalization |
| Unit (action) | Each action tool: `preview` mutates nothing; denied cross-scope; ambiguous resolution; `execute` re-guards; service called with correct principal + idempotency key; destructive typed-confirm; `max-bulk-impact` refusal |
| Unit (confirm) | Token generate/validate/consume; expiry; replay; wrong-user; cancel path |
| Integration | SSE not wrapped; read cache-hit; `confirmRequired` then `/confirm` → execute → audit; `/cancel`; rate-limit 429; **two-school tenant isolation**; parent vs teacher vs admin tool availability |
| Authorization oracle | Parameterized test over the Appendix table: tool reachable ⇔ endpoint reachable, per role |
| E2E (opt-in) | One RestAssured confirm→execute flow per persona against real Anthropic, env-gated, excluded from CI default (mirrors WhatsApp WireMock pattern) |

Target: **80%+ coverage** before `hardening`.

---

## 17. Success Criteria

- [ ] Parent: correct localized answers for all read questions **and** can respond to an absence
      alert / acknowledge homework & announcements via confirmed actions.
- [ ] Teacher: can mark attendance, mark-all-present, create/update grades, create/publish/update/
      archive homework, and post a CLASS announcement — each only after confirming a valid token.
- [ ] School admin: can add/update/delete students, create/update/delete classes, enroll, link
      parents, manage subjects/assignments, post any-scope announcements, recall, delete grades.
- [ ] Mirror-roles proven: a teacher/parent is **refused** every admin-only action (test-proven),
      with a helpful localized message.
- [ ] No mutation ever occurs without a valid, single-use, user-bound confirmation token.
- [ ] A parent/teacher/admin cannot affect another tenant's data (two-school fixture, test-proven).
- [ ] LLM never receives SQL/DB handles or UUIDs; only the role-scoped tool catalog + results.
- [ ] Every ask **and** every preview/execute/cancel writes an `audit_logs` row.
- [ ] Double-confirm is idempotent; replay/expired/wrong-user tokens mutate nothing.
- [ ] Reads stream over SSE un-wrapped; `/confirm` returns a normal wrapped `ApiResponse`.
- [ ] Ships dark (`enabled=false`, `actions.enabled=false`); `mvn -B -ntp verify` green, 80%+ cov.

---

## 18. Appendix — Endpoint → Authorization → Tool mapping (the CI oracle)

| Endpoint | `@PreAuthorize` (today) | Assistant tool | Kind | Roles |
|---|---|---|---|---|
| `POST /attendance/mark` | admin **or** (teacher ∧ `teacherTeaches(classId)`) | `mark_attendance` | A | Teacher, Admin |
| `POST /attendance/mark-all-present` | admin **or** (teacher ∧ `teacherTeaches`) | `mark_all_present` | A | Teacher, Admin |
| `GET /attendance/roster` | admin **or** (teacher ∧ `teacherTeaches`) | `get_class_attendance` | R | Teacher, Admin |
| `GET /attendance/history` | admin **or** teacher **or** `parentLinkedTo(studentId)` | `get_student_attendance` / `get_child_attendance` | R | All |
| `POST /attendance/{id}/parent-response` | parent (+ service link) | `respond_to_absence_alert` | A | Parent |
| `POST /grades` | admin **or** (teacher ∧ `teacherTeaches`) | `create_grade` | A | Teacher, Admin |
| `PATCH /grades/{id}` | admin **or** teacher (+ service ownership) | `update_grade` | A | Teacher, Admin |
| `DELETE /grades/{id}` | admin | `delete_grade` | A | Admin |
| `GET /grades?studentId` | admin/teacher **or** `parentLinkedTo` | `get_student_grades` / `get_child_grades` | R | All |
| `GET /grades?classId` | admin **or** `teacherTeaches` | `get_class_grades` | R | Teacher, Admin |
| `POST /homework` | admin **or** (teacher ∧ `teacherTeaches`) | `create_homework` | A | Teacher, Admin |
| `POST /homework/{id}/publish` | admin **or** `isHomeworkAuthor` | `publish_homework` | A | Teacher, Admin |
| `PATCH /homework/{id}` | admin **or** `isHomeworkAuthor` | `update_homework` | A | Teacher, Admin |
| `DELETE /homework/{id}` | admin **or** `isHomeworkAuthor` | `archive_homework` | A | Teacher, Admin |
| `GET /homework` | admin **or** (teacher ∧ `teacherTeaches`) | `list_homework` | R | Teacher, Admin |
| `GET /homework?childId` | parent | `get_child_homework` | R | Parent |
| `POST /homework/{id}/acknowledge` | parent | `acknowledge_homework` | A | Parent |
| `POST /announcements` | admin (any scope) **or** (teacher ∧ CLASS ∧ `teacherTeaches`) | `post_announcement` / `post_class_announcement` | A | Teacher (CLASS), Admin |
| `POST /announcements/{id}/recall` | admin | `recall_announcement` | A | Admin |
| `POST /announcements/{id}/acknowledge` | parent ∧ `parentReceivedAnnouncement` | `acknowledge_announcement` | A | Parent |
| `GET /announcements/{id}/recipients` | admin | `get_announcement_recipients` | R | Admin |
| `POST /students` | admin | `add_student` | A | Admin |
| `PATCH /students/{id}` | admin | `update_student` | A | Admin |
| `DELETE /students/{id}` | admin | `delete_student` | A | Admin |
| `POST /students:bulk-import` | admin | `bulk_import_students` | A* (UI handoff) | Admin |
| `POST /classes` | admin | `create_class` | A | Admin |
| `PATCH /classes/{id}` | admin | `update_class` | A | Admin |
| `DELETE /classes/{id}` | admin | `delete_class` | A | Admin |
| `GET /classes/my-classes` | teacher | `list_my_classes` | R | Teacher |
| `POST /classes/{classId}/enrollments` | admin | `enroll_student` | A | Admin |
| `GET /classes/{classId}/enrollments` | admin **or** `teacherTeaches` | `get_class_enrollments` | R | Teacher, Admin |
| `DELETE /enrollments/{id}` | admin | `remove_enrollment` | A | Admin |
| `POST /parent-links` | admin | `link_parent_to_student` | A | Admin |
| `DELETE /parent-links/{id}` | admin | `remove_parent_link` | A | Admin |
| `GET /parents/me/children` | parent | `list_my_children` | R | Parent |
| `POST /subjects`, `PATCH/DELETE /subjects/{id}` | admin | `create/update/delete_subject` | A | Admin |
| `POST/DELETE class-subjects` | admin | `add_subject_to_class` / `remove_class_subject` | A | Admin |
| `POST/DELETE teacher-subject-assignments` | admin | `assign_teacher_to_subject` / `remove_teacher_assignment` | A | Admin |
| `POST /schools/{id}/users` | **super-admin** | `add_user` | A | Super Admin (deferred §13) |
| `POST/…/schools` (create/suspend/reactivate/settings) | **super-admin** | school-mgmt tools | A | Super Admin (deferred §13) |

> This table is the contract: an automated test iterates it and asserts each tool is registered for
> exactly the roles whose endpoint guard it would pass — no more, no less.

---

## 19. Key Files to Integrate With

**Services the tools wrap:** `attendance/AttendanceService`, `grades/GradeService`,
`homework/HomeworkService`, `announcements/service/AnnouncementService`,
`classes/service/{StudentService,SchoolClassService,EnrollmentService,ParentStudentLinkService,
ParentChildrenService}`, `subjects/service/{SubjectService,ClassSubjectService,
TeacherSubjectAssignmentService}`, `identity/UserService` (super-admin, deferred).

**Patterns to mirror:**
- `common/security/PermissionsHelper.java` — **inject and call directly** in every tool (the guard).
- `attendance/AttendanceController.java`, `homework/HomeworkController.java`,
  `grades/GradesController.java` — principal resolution (`StaffPrincipal`/`ParentPrincipal`) + the
  exact guard each action must replicate.
- `common/idempotency/IdempotencyService.java` — idempotent execute.
- `common/audit/AuditService.java` — audit signature (HashMap metadata, never `Map.of`).
- `common/web/ApiResponseBodyAdvice.java` — SSE non-wrapping invariant.
- `common/security/SecurityConfig.java` — register `/assistant/**` routes authenticated.
- `common/tenancy/TenantContext.java` — `require()` for `schoolId`.

---

## 20. Open Questions (for product, before Phase 3)

1. **Multi-step batching:** should "add Sara and enroll her in 4A" allow a single combined confirm,
   or always one-confirm-per-mutation (current plan = one at a time)?
2. **Destructive actions via chat at all?** Some orgs may want `delete_*` blocked from the assistant
   entirely (UI-only). `actions.enabled` + a per-tool allow-list can enforce that.
3. **Teacher → admin handoff:** when a teacher asks for an admin action, do we just refuse, or
   auto-draft a request/announcement to the admin? (Plan currently refuses + offers to draft.)
4. **Durable ledger:** ship `assistant_action` table in v2, or rely on `audit_logs` until analytics
   needs it?
