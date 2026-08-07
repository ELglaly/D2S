# SchoolBridge — Domain Glossary

> Terms as used in code and conversation. If a term you hear doesn't match
> anything here, ask before assuming — domain mismatches cause the worst
> bugs (wrong permission, wrong tenant scope).

## Tenancy

- **School / tenant** — the isolation boundary. Every `TenantEntity` row
  carries a `school_id`; Hibernate `@Filter` scopes queries to the caller's
  school. See ADR-002 and `feedback_hibernate_filter_findbyid_bypass.md`.
- **Platform admin** (`PlatformAdmin`) — cross-tenant super-user, lives in a
  *separate* table from `User`/`UserRole.SUPER_ADMIN`. Distinguished from a
  tenant-scoped `SUPER_ADMIN` role by `SubjectKind` on the refresh token.

## Identity & access

- **Role** (`UserRole`) — one of `SUPER_ADMIN`, `SCHOOL_ADMIN`, `TEACHER`,
  `PARENT`. Single-role model: one role per user, not a set.
- **Permission** (`Permission`) — fine-grained capability
  (`HOMEWORK_PUBLISH`, `ATTENDANCE_RECORD`, …), enforced via
  `@RequirePermission` + AOP. Mirrors a DB row in `permissions`;
  `role_permissions` maps roles → permissions at runtime (role-keyed cache).
  `MANAGE_ROLES`/`MANAGE_PERMISSIONS` are seeded to `SUPER_ADMIN` only.
- **Row-ownership / identity gate** — a check narrower than a permission
  (e.g. "this parent owns this child", "this teacher teaches this class").
  Implemented as `@PreAuthorize` alongside the permission aspect, not folded
  into `Permission` itself.

## Classes & people

- **`SchoolClass`** — a classroom/section (avoid the bare word "class" in
  code — reserved word collision and ambiguity with `UserRole`/enum
  classes).
- **Student** — enrolled learner; distinct entity from `User` (a student may
  or may not have a login).
- **Enrollment** — a student's membership in a `SchoolClass`.
- **`ParentStudentLink`** — the parent-child relationship a `PARENT` role
  user is scoped to. Most parent-facing tools/endpoints resolve "my child"
  through this link, not a direct student ID from the caller.
- **Subject** — per-school subject catalog entry (`subjects` module),
  distinct from grade *records*.

## Academics

- **Homework item** (`HomeworkItem`) — has a `HomeworkStatus`:
  `DRAFT → PUBLISHED → ARCHIVED`. Only `PUBLISHED` items are visible to
  parents/students and eligible for reminder sweeps.
- **Homework recipient** (`HomeworkRecipient`) — per-parent delivery/ack row
  for a homework item, with a `HomeworkDeliveryStatus`.
- **Grade record** (`GradeRecordRepository`/`grades` module) — a scored
  assessment entry, gated by `GRADE_*` permissions.

## Communication

- **Announcement** — school- or class-targeted broadcast; supports
  acknowledgement tracking (`AnnouncementRecipient`) and recall.
- **Absence alert** — attendance-triggered notification to a parent,
  dispatched through the outbox → `AttendanceAlertConsumer`.
- **Outbox** — a row written in the *same transaction* as the domain change,
  later published (RabbitMQ) and consumed by `integrations`. Prevents
  dual-write inconsistency between DB state and dispatched notifications.
  Payloads are built with `HashMap`, never `Map.of(...)`
  (`feedback_outbox_audit_mapof_npe.md`).
- **Dispatch channel** (`NotificationChannel`) — WhatsApp, push (FCM), or
  SMS; `NotificationDispatcher` picks the channel, `integrations/*` holds
  the concrete clients.

## AI Assistant

- **Tool** — a capability exposed to the LLM; a thin adapter over an
  existing service so a tool call enforces the *same* authorization and
  business rules as the equivalent REST endpoint (see ADR-005).
- **`ToolKind`** — `READ` (query) vs `ACTION` (mutating). Action tools may
  require confirmation (`assistant/confirm`) before executing.
- **`ToolDomain`** — coarse intent bucket (`ATTENDANCE`, `HOMEWORK`, …)
  derived from a tool's package, used to gate which tools are offered for a
  given query instead of advertising the caller's entire role catalog
  (dominant input-token cost).
- **RAG / knowledge document** (`assistant/rag`) — pgvector-backed retrieval
  context the assistant can draw on, separate from tool-based actions.
- **Conversation** — the assistant's chat session/thread; `ConversationMessage`
  is a single turn.

## Cross-cutting

- **Idempotency** (`common/idempotency`) — dedup key for retried mutating
  requests (e.g. WhatsApp webhook redelivery).
- **Blind index** (`common/crypto`) — deterministic hash alongside an
  AES-GCM-encrypted PII column, enabling equality lookups without decrypting.
- **Response envelope** — the consistent success/data/error/meta wrapper
  every API response is normalized into (`common/web`,
  `ResponseBodyAdvice` — must check both Jackson converter type *and* the
  `com.schoolbridge.api` package, see
  `feedback_response_body_advice_exclusions.md`).
