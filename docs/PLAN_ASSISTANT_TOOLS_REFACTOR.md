# Plan — Assistant Tool Layer: Organization, Messages, Security, SOLID

> Scope: `com.schoolbridge.api.assistant.tools.**`. Status as of 2026-06-17.
> Ships dark behind `schoolbridge.assistant.enabled` + `schoolbridge.assistant.actions.enabled`.

## 0. Premise correction (read first)

The review template assumed **Spring AI `@Tool` annotated methods** with per-method
`@RequirePermission`. **That is not how this codebase works.** Reality:

- **No `@Tool` annotation anywhere.** Tools are a hand-rolled abstraction:
  `Tool` → `ReadTool` / `ActionTool` (`tools/Tool.java`, `ReadTool.java`, `ActionTool.java`),
  each tool a `@Component` bean.
- `ToolRegistry` collects `List<Tool>` (Spring auto-wires every bean) and role-filters per request
  (`ToolRegistry.toolsFor`). Adding a tool = drop a new `@Component`. **No switch/if-chains.**
- Tools are mapped to `LlmToolSpec(name, description, inputSchema)` and handed to provider gateways
  (Anthropic / DeepSeek / Gemini / SpringAi / Disabled). `description()` + schema field text are
  **LLM-facing prompt strings (English) — NOT user-facing**. Do not i18n them.
- `org.springframework.ai` appears only in the optional `SpringAiLlmGateway`. The tool layer is
  provider-agnostic by design.

**Net:** the framework is already strong on SRP/OCP/DIP. The genuine findings are narrower than the
template implies. Don't "fix" things that aren't broken (no god-tool split needed, registry pattern
already present).

## 1. Tool inventory

51 tools: **19 read**, **32 action**. All `@Component`, constructor-injected, scope-checked
in-tool via `PermissionsHelper` (`teacherTeaches`, `parentLinkedTo`) + role gate via `Tool.roles()`.
All user-facing rejection text already flows through `MessageResolver` (`assistant.*` keys).

### Read tools (`tools/read/`, kind=READ)

| Tool | name | roles | backing service |
|---|---|---|---|
| ListMyChildrenTool | list_my_children | PARENT | resolvers |
| GetChildAttendanceTool | get_child_attendance | PARENT | AttendanceService |
| GetChildAbsenceCountTool | get_child_absence_count | PARENT | AttendanceService |
| GetChildHomeworkTool | get_child_homework | PARENT | HomeworkService |
| GetChildGradesTool | get_child_grades | PARENT | GradeService |
| GetUnacknowledgedAnnouncementsTool | get_unacknowledged_announcements | PARENT | AnnouncementService |
| ListMyClassesTool | list_my_classes | TEACHER | SchoolClassService |
| GetClassAttendanceTool | get_class_attendance | TEACHER, SCHOOL_ADMIN | AttendanceService |
| GetStudentAttendanceTool | get_student_attendance | TEACHER, SCHOOL_ADMIN | AttendanceService |
| GetClassEnrollmentsTool | get_class_enrollments | TEACHER, SCHOOL_ADMIN | EnrollmentService |
| GetClassGradesTool | get_class_grades | TEACHER, SCHOOL_ADMIN | GradeService |
| GetStudentGradesTool | get_student_grades | TEACHER, SCHOOL_ADMIN | GradeService |
| ListHomeworkTool | list_homework | TEACHER, SCHOOL_ADMIN | HomeworkService |
| GetHomeworkRecipientsTool | get_homework_recipients | TEACHER, SCHOOL_ADMIN | HomeworkService |
| GetAnnouncementRecipientsTool | get_announcement_recipients | TEACHER, SCHOOL_ADMIN | AnnouncementService |
| ListStudentsTool | list_students | TEACHER, SCHOOL_ADMIN | StudentService |
| ListClassesTool | list_classes | TEACHER, SCHOOL_ADMIN | SchoolClassService |
| ListSubjectsTool | list_subjects | TEACHER, SCHOOL_ADMIN | SubjectService |
| ListParentLinksTool | list_parent_links | SCHOOL_ADMIN | ParentStudentLinkService |

### Action tools (`tools/action/`, kind=ACTION, two-phase preview→confirm)

attendance: MarkAttendanceTool, MarkAllPresentTool, RespondToAbsenceAlertTool
grades: CreateGradeTool, UpdateGradeTool, DeleteGradeTool*(destructive)
homework: CreateHomeworkTool, PublishHomeworkTool, UpdateHomeworkTool, ArchiveHomeworkTool, AcknowledgeHomeworkTool
announcements: PostAnnouncementTool, PostClassAnnouncementTool, RecallAnnouncementTool*, AcknowledgeAnnouncementTool
students: AddStudentTool, UpdateStudentTool, DeleteStudentTool*
classes: CreateClassTool, UpdateClassTool, DeleteClassTool*, EnrollStudentTool, RemoveEnrollmentTool
subjects: CreateSubjectTool, UpdateSubjectTool, DeleteSubjectTool*, AddSubjectToClassTool, RemoveClassSubjectTool, AssignTeacherToSubjectTool, RemoveTeacherAssignmentTool
parent-links: LinkParentToStudentTool, RemoveParentLinkTool

`*` = `destructive()` → typed confirmation. `AbstractActionTool` owns bulk-cap, token issue,
Redis store, single-use consume, user/expiry re-check, idempotency key. Subclass writes only
`prepare()` + `doExecute()`. **Base class already exists — Step 9 of the template is done.**

## 2. FINDING #1 (high) — hardcoded bilingual confirmation summaries

Every action tool's `prepare()` builds `summaryEn` / `summaryAr` by **inline string concatenation**,
bypassing `MessageResolver`. Example `CreateGradeTool`:

```java
String en = "I'll record " + subject + " = " + value + " (" + period + ") for "
    + student.value().fullName() + ". Confirm?";
String ar = "سأسجّل " + subject + " = " + value + " (" + period + ") لـ "
    + student.value().fullName() + ". أؤكّد؟";
```

These strings are **user-facing** (emitted to the client via `AssistantController.confirmChunk` as
`summary`/`summaryEn`). They satisfy "bilingual" but violate the project's MessageResolver/bundle
convention, scatter copy across 32 files, and make tone/wording changes a 64-edit chore. This is the
single biggest maintainability + i18n debt in the layer.

### Fix — keyed summaries, both languages from the bundle

`MessageResolver` already resolves by `Locale`. Add a helper to `AbstractActionTool` that renders a
key in **both** ar + en regardless of request locale (preview ships both):

```java
// AbstractActionTool
protected String msgIn(Locale locale, String key, Object... args) {
  return actions.messages().get(locale, key, args); // add overload to MessageResolver
}
```
(If `MessageResolver` has no explicit-locale overload, add one delegating to the underlying
`MessageSource.getMessage(key, args, locale)`.)

Then per tool:

```java
Map<String,Object> impact = ...;
return ready(resolved,
    msgIn(AR, "assistant.action.create_grade.summary", subject, value, period, student.value().fullName()),
    msgIn(EN, "assistant.action.create_grade.summary", subject, value, period, student.value().fullName()),
    impact, 1);
```

`AR = Locale.forLanguageTag("ar")`, `EN = Locale.ENGLISH` as constants in `AbstractActionTool`.

## 3. FINDING #2 (high, security) — dual authz model; tool path bypasses `@RequirePermission`

Two parallel authorization systems now exist:

1. **Controllers**: DB-backed `@RequirePermission(Permission.X)` + `PermissionAspect`
   (RBAC sweep complete, 325 tests).
2. **Assistant tools**: coarse gate via hardcoded `Tool.roles()` sets + in-tool scope helpers.
   Tools call **services directly** (`grades.create(...)`), **not** controllers — so the
   `PermissionAspect` never runs on the tool path.

Risk: if an admin re-maps `role_permissions` in the DB (the whole point of the RBAC layer), the
assistant's tool visibility **does not follow** — each tool's role set is compiled in. A role could
lose `GRADE_CREATE` at the controller layer yet still create grades via the assistant. The two models
can silently diverge.

### Options

| Option | What | Effort | Recommendation |
|---|---|---|---|
| A | Move `@RequirePermission` from controllers down to **service** methods; tools inherit enforcement for free | high (touches every service + controller test) | best long-term, but large blast radius |
| B | Each tool declares a `Permission required()`; `ToolRegistry.toolsFor` consults `EffectivePermissionService.has(role, perm)` instead of (or in addition to) `roles()` | medium | **recommended** — keeps tool path, makes the DB the single source of truth, no service churn |
| C | Status quo + a guard test asserting every tool's `roles()` equals the roles that hold the matching `Permission` in seed data | low | minimum bar; ship now, do B next |

**Recommended:** C now (cheap regression guard), then B. Map each tool → `Permission`:

```
read grades  → GRADE_READ      create_grade → GRADE_CREATE   delete_grade → GRADE_DELETE
mark_attendance → ATTENDANCE_RECORD   list_students → STUDENT_READ   add_student → STUDENT_MANAGE
post_announcement → ANNOUNCEMENT_SEND   recall_announcement → ANNOUNCEMENT_MANAGE   ... (full table in §7)
```

`Permission` enum already has every value needed except a generic `USER_READ` (see §6). No new
permissions required for existing tools.

## 4. FINDING #3 (medium) — monolithic message bundle

All `assistant.*` keys live in one `messages.properties` (+ `_en` / `_ar`). Per the template's Step 5
this can split per-domain, but Spring's `messageSource.basenames` would need every basename listed and
the rest of the app uses one bundle. **Recommendation: keep one bundle, enforce the key convention
below.** Splitting files is cosmetic and breaks the app-wide single-bundle norm — defer unless the
file becomes unmanageable.

### Key convention (apply to new summary keys)

```
assistant.action.<tool_name>.summary            # confirm prompt, {n} positional args
assistant.action.<tool_name>.summary.destructive# optional stronger wording for destructive
assistant.<domain>.<reason>                      # existing clarify/denied keys (unchanged)
```
Audience split (`user.*`/`admin.*`/`system.*`) from template Step 6 is **not** warranted here: the
assistant only ever speaks to end users; admin/system diagnostics go to logs + audit
(`AssistantAuditRecorder`), not message bundles. Skip it.

## 5. Package reorganization — flat read/action → domain

Current: `tools/read/*` (19 flat) + `tools/action/*` (32 flat) + `tools/support/*` + base in `tools/`.
Proposed: group by **domain**, keep read/action as the leaf distinction (preserves the kill-switch
clarity in `ToolRegistry` which filters on `kind()`):

```
assistant/tools
├── shared/            # Tool, ReadTool, ActionTool, ToolKind, ToolResult, ToolContext,
│                      # PreviewOutcome, ToolRegistry, AbstractActionTool, ActionSupport, support/*
├── student/           # ListStudents, AddStudent, UpdateStudent, DeleteStudent, ListMyChildren
├── classes/           # ListClasses, ListMyClasses, Create/Update/DeleteClass,
│                      # GetClassEnrollments, Enroll/RemoveEnrollment
├── grades/            # GetChild/Student/ClassGrades, Create/Update/DeleteGrade
├── attendance/        # GetClass/Student/Child attendance, GetChildAbsenceCount,
│                      # MarkAttendance, MarkAllPresent, RespondToAbsenceAlert
├── homework/          # ListHomework, GetHomeworkRecipients, GetChildHomework,
│                      # Create/Publish/Update/Archive/AcknowledgeHomework
├── announcements/     # GetUnacknowledged, GetAnnouncementRecipients,
│                      # Post/PostClass/Recall/AcknowledgeAnnouncement
├── subjects/          # ListSubjects, Create/Update/DeleteSubject, AddSubjectToClass,
│                      # RemoveClassSubject, AssignTeacherToSubject, RemoveTeacherAssignment
└── parents/           # ListParentLinks, LinkParentToStudent, RemoveParentLink
```

Migration: pure package moves, zero behavior change. `ToolRegistry` discovers by type
(`List<Tool>`), so moves don't touch wiring. Steps:
1. Create domain packages under `tools/`.
2. Move classes; update `package` + imports (IDE move-refactor; `removeUnusedImports` will tidy).
3. Move base/abstractions to `tools/shared/`.
4. `mvn spotless:apply && mvn -B -ntp verify` — green gate (tests reference classes by import, so
   they recompile clean).

Low risk, mechanical. Do it in **one commit, no logic edits** so review is trivial.

## 6. Missing tools (real gaps in current modules)

Skeletons use the **actual** framework (`ReadTool` / `AbstractActionTool`), not Spring AI `@Tool`.

### 6.1 ListAnnouncementsTool (read) — `announcements/`
Purpose: list recent announcements for the caller's scope (parents see theirs; staff see class/school).
Today only "unacknowledged" + "recipients" reads exist; no plain list.
Permission/roles: `PARENT, TEACHER, SCHOOL_ADMIN` → `ANNOUNCEMENT_READ`.
```java
@Component
public class ListAnnouncementsTool implements ReadTool {
  private final AnnouncementService announcements;
  public ListAnnouncementsTool(AnnouncementService announcements){ this.announcements = announcements; }
  @Override public String name(){ return "list_announcements"; }
  @Override public String description(){ return "List recent announcements for the caller."; }
  @Override public JsonNode inputSchema(){
    return Schema.builder().number("limit","Max items (default 10)", false).build();
  }
  @Override public Set<UserRole> roles(){ return Set.of(UserRole.PARENT, UserRole.TEACHER, UserRole.SCHOOL_ADMIN); }
  @Override public ToolResult execute(JsonNode args, ToolContext ctx){
    return ToolResult.ok(announcements.listForPrincipal(ctx.principal(), Args.intOr(args,"limit",10)));
  }
}
```

### 6.2 ListTeachersTool (read) — `student/`→ better `staff/` (or reuse classes/)
Purpose: admin lists teachers (resolvers already expose `teachers(...)` but no LLM tool surfaces it).
Permission/roles: `SCHOOL_ADMIN`. Needs `USER_READ` permission — **add to `Permission` enum**
(only genuinely missing permission). Seed `USER_READ` to SCHOOL_ADMIN + SUPER_ADMIN.
```java
@Component
public class ListTeachersTool implements ReadTool {
  private final UserService users;
  public ListTeachersTool(UserService users){ this.users = users; }
  @Override public String name(){ return "list_teachers"; }
  @Override public String description(){ return "List teachers in the school."; }
  @Override public JsonNode inputSchema(){ return Schema.builder().build(); }
  @Override public Set<UserRole> roles(){ return Set.of(UserRole.SCHOOL_ADMIN); }
  @Override public ToolResult execute(JsonNode args, ToolContext ctx){ return ToolResult.ok(users.listTeachers(ctx.schoolId())); }
}
```

### 6.3 GetClassGradeSummaryTool (read) — `grades/`
Purpose: class average / distribution for a subject+period (aggregate, not row dump). High value for
"how did 7A do in Math this term". Permission/roles: `TEACHER, SCHOOL_ADMIN` → `GRADE_READ`.
Skeleton mirrors `GetClassGradesTool` + service aggregate call.

### 6.4 GetAttendanceSummaryTool (read) — `attendance/`
Purpose: class attendance rate over a date range. Permission/roles: `TEACHER, SCHOOL_ADMIN` →
`ATTENDANCE_READ`. Skeleton mirrors `GetClassAttendanceTool` with `from`/`to` schema fields.

> Deliberately **not** adding: school-settings, whatsapp-diagnostics, user-management mutations.
> Those are admin-console concerns, high blast radius, low NL value — keep out of the assistant.

## 7. Tool → Permission map (for §3 option B/C)

```
list_my_children                 (n/a, identity-scoped)     create_grade        GRADE_CREATE
get_child_grades                 GRADE_READ                 update_grade        GRADE_UPDATE
get_student_grades/class_grades  GRADE_READ                 delete_grade        GRADE_DELETE
get_*_attendance                 ATTENDANCE_READ            mark_attendance     ATTENDANCE_RECORD
get_child_absence_count          ATTENDANCE_READ            mark_all_present    ATTENDANCE_RECORD
list_homework/get_homework_*     HOMEWORK_READ             respond_to_absence  ATTENDANCE_RECORD
list_students                    STUDENT_READ               create_homework     HOMEWORK_CREATE
list_classes/list_my_classes     CLASS_READ                 publish_homework    HOMEWORK_PUBLISH
get_class_enrollments            CLASS_READ                 update_homework     HOMEWORK_UPDATE
list_subjects                    SUBJECT_READ               archive_homework    HOMEWORK_DELETE
list_parent_links                PARENT_LINK_MANAGE         acknowledge_homework HOMEWORK_ACK
get_unacknowledged_announcements ANNOUNCEMENT_READ          add/update_student   STUDENT_MANAGE
get_announcement_recipients      ANNOUNCEMENT_READ          delete_student       STUDENT_MANAGE
                                                            create/update/delete_class  CLASS_MANAGE
                                                            enroll/remove_enrollment     ENROLLMENT_MANAGE
                                                            *_subject / *_class_subject  SUBJECT_MANAGE
                                                            assign/remove_teacher        SUBJECT_MANAGE
                                                            link/remove_parent_link      PARENT_LINK_MANAGE
                                                            post*/post_class_announcement ANNOUNCEMENT_SEND
                                                            recall_announcement          ANNOUNCEMENT_MANAGE
                                                            acknowledge_announcement     ANNOUNCEMENT_READ
```
Every value exists in `Permission` except `USER_READ` (add it, §6.2).

## 8. SOLID summary

| Principle | Rating | Justification |
|---|---|---|
| SRP | Excellent | One tool = one business action already. No god-tools. Don't split anything. |
| OCP | Excellent | New tool = new `@Component`; `ToolRegistry` collects by type. No switch/if-chains over tool types. |
| LSP | Good | Uniform `Tool`/`ReadTool`/`ActionTool` contracts; `ToolResult` 4-shape contract; `AbstractActionTool` enforces preview→execute. |
| ISP | Good (minor smell) | `Tool` interface minimal. `ActionSupport` bundles 6 collaborators — convenience aggregate; tools depend on the whole bundle. Acceptable; documented tradeoff. |
| DIP | Excellent | Constructor injection throughout; depends on services + `MessageResolver` abstraction; no `new Repository()`. |

The layer is **already well-architected**. Work is debt-paydown (messages, authz alignment) +
reorg + a few additions — not a redesign.

## 9. Phased execution (each phase its own commit, green gate)

- **P1 — Package reorg** (mechanical, no logic). Move to domain packages. `verify` green.
- **P2 — Message centralization.** Add `MessageResolver.get(locale,...)` overload + `AbstractActionTool.msgIn`
  + `AR`/`EN` constants. Add `assistant.action.<tool>.summary` keys (en + ar). Replace inline
  concatenation in all 32 action tools. Update action tool tests asserting on summary text.
- **P3 — Authz guard (§3 option C).** Regression test: each tool's `roles()` ⊇/= roles holding its
  mapped `Permission` in seed data. Add `USER_READ`, seed it.
- **P4 — Missing tools** (§6.1–6.4) + their summary/clarify keys + role/permission tests.
- **P5 (later, separate PR) — Authz option B.** `Tool.required()` → `EffectivePermissionService` in
  `ToolRegistry.toolsFor`. Make DB the single source of truth for tool visibility.

Gate every phase: `mvn spotless:apply && mvn -B -ntp verify`.
Watch the known gotchas: `removeUnusedImports` strips imports added before first use; build
`impact`/audit payloads with `HashMap` not `Map.of`; keep `ResponseBodyAdvice` exclusions intact.
