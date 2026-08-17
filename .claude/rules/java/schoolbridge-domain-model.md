# SchoolBridge Domain Model

Entity/enum catalog grounded in the actual source (`src/main/java/com/schoolbridge/api/`). Do not
invent enum values — grep the real `public enum` file if one isn't listed here, values drift as the
code evolves. Last verified: 2026-08-14.

> Money is `NUMERIC`/`BigDecimal`, never `double`. Timestamps are `Instant` (UTC), converted to local
> zone at the controller boundary. Tenant-scoped entities extend `TenantEntity`
> (`common/persistence`) and carry a `school_id`.

## Tenancy

- **School** (`tenant` module) — the isolation boundary. Fields include `SchoolStatus`,
  `SubscriptionTier`, default `Language`.
- **`PlatformAdmin`** — cross-tenant super-user, a *separate* table from `User`. Distinguished from a
  tenant-scoped `SUPER_ADMIN` role by `SubjectKind` on the refresh token.

## identity module

### `User`
Tenant-scoped account. Role via `UserRole` (single-role model — one per user, not a set), status via
`UserStatus`.

### `SubjectKind`
`USER, PLATFORM_ADMIN` — which table a JWT subject resolves against.

### Device (`identity/device`)
Push-notification device token. `DevicePlatform`: `ANDROID, IOS`.

## classes module

### `SchoolClass`
A classroom/section. (Avoid the bare word "class" elsewhere in code — reserved word collision and
ambiguity with `UserRole`/enum classes.)

### `Student`
Enrolled learner; a distinct entity from `User` — a student may or may not have a login.
`StudentStatus`: `ACTIVE, INACTIVE, GRADUATED, SUSPENDED`.

### `Enrollment`
A student's membership in a `SchoolClass`.

### `ParentStudentLink`
The parent-child relationship a `PARENT`-role user is scoped to. `RelationshipType`: `MOTHER,
FATHER, GUARDIAN`. Most parent-facing reads resolve "my child" through this link, never a bare
student id supplied by the caller.

## subjects module

### `Subject`
Per-school subject catalog entry, distinct from grade *records*. `SubjectStatus`: `ACTIVE,
INACTIVE`.

### Class-subject / teacher assignment
Join entities linking a `SchoolClass` ↔ `Subject` ↔ teaching `User`.

## grades module

### Grade record
A scored assessment entry, gated by `GRADE_{CREATE,READ,UPDATE,DELETE}` permissions.

## announcements module

### Announcement
School- or class-targeted broadcast. `AnnouncementStatus`: `DRAFT, SCHEDULED, SENDING, SENT,
RECALLED`. `AnnouncementScope`: `SCHOOL, GRADE, CLASS, CUSTOM`.

### `AnnouncementRecipient`
Per-recipient delivery/ack row. `DeliveryStatus`: `QUEUED, DEFERRED, …` (check the enum file for the
full terminal set — verified members beyond `QUEUED`/`DEFERRED` were not re-confirmed at last write).

## attendance module

### Attendance record
`AttendanceStatus`: `PRESENT, ABSENT, LATE, EXCUSED`.

### Absence alert
Attendance-triggered notification to a parent, dispatched through the outbox →
`AttendanceAlertConsumer`. `AttendanceAlertStatus`: `PENDING, DEFERRED, SENT, FAILED`.

## homework module

### `HomeworkItem`
`HomeworkStatus`: `DRAFT → PUBLISHED → ARCHIVED`. Only `PUBLISHED` items are visible to
parents/students and eligible for reminder sweeps.

### `HomeworkRecipient`
Per-parent delivery/ack row for a homework item. `HomeworkDeliveryStatus`: `PENDING, DEFERRED,
SUPPRESSED, SENT, FAILED`.

## attachments module

### Attachment
`AttachmentStatus`: `PENDING → UPLOADED → CLEAN`, or `REJECTED` (size/MIME/checksum) / `INFECTED`
(AV positive). `PENDING` means an upload URL was issued but the API is never told the client's PUT
landed — a sweeper deletes long-abandoned `PENDING` rows. `AvResult`: `CLEAN, INFECTED, SKIPPED`.

## notifications module

### Notification preference
Per-user quiet hours, channel order, per-category opt-out. `NotificationCategory`: `ANNOUNCEMENT,
HOMEWORK, ATTENDANCE`.

## integrations module

`NotificationChannel`: `PUSH, WHATSAPP, SMS`. `NotificationDispatcher` walks the channel list and
stops at the first channel that **accepts** — a stub/no-op channel must report failure, never claim
success, or it silently swallows every notification behind it in the walk
(`docs/COMMON_MISTAKES.md` #15).

## assistant module (ships dark)

- **Tool** — an LLM-callable capability; a thin adapter over an existing service so a tool call
  enforces the same authorization/business rules as the equivalent REST endpoint.
- **`ToolKind`**: `READ` (query) vs `ACTION` (mutating — may require `assistant/confirm` before
  executing, see `ConfirmIntent.Decision`).
- **`ToolDomain`** — coarse intent bucket (e.g. `ATTENDANCE`, `HOMEWORK`) derived from a tool's
  package; gates which tools are offered for a query instead of advertising the caller's entire
  permission catalog (dominant input-token cost).
- **`DocType`/`IngestStatus`** (`assistant/rag`) — pgvector-backed knowledge document lifecycle,
  separate from tool-based actions.
- **Conversation** / `ConversationMessage` — the assistant's chat thread; `MessageRole` tags each
  turn.

## common module (cross-cutting, not business data)

### `TenantEntity` (`common/persistence`)
Base class for every tenant-scoped entity — carries `school_id`, scoped by a Hibernate `@Filter`.
**Every repository on a `TenantEntity` subclass must override `findById` with an explicit `@Query`**
— the filter does not apply to `EntityManager.find()` (`docs/COMMON_MISTAKES.md` #1,
`feedback_hibernate_filter_findbyid_bypass.md`).

### `ErrorType` (`common/error`)
`NOT_FOUND(404), VALIDATION(422), AUTHENTICATION(401), AUTHORIZATION(403), CONFLICT(409),
RATE_LIMIT(429), INTEGRATION(502), TENANT_SECURITY(403), INTERNAL(500)` — each pairs an
`HttpStatus` with an i18n message key (`error.*`).

### `OutboxStatus` (`common/outbox`)
`PENDING, PUBLISHED, FAILED, DEAD`. Payloads are built with `HashMap`/`LinkedHashMap`, never
`Map.of(...)` — outbox payloads carry naturally-nullable fields and `Map.of` throws NPE on the first
null (`docs/COMMON_MISTAKES.md` #3).

### `Permission` (`common/security/authz`)
Fine-grained capability enforced via `@RequirePermission` + AOP; see the full list and the
role→permission mapping model in `.claude/rules/java/schoolbridge-modules.md` under "Authorization
Model". Mirrors DB rows in `permissions`/`role_permissions` with a role-keyed runtime cache.

## Chart of cross-module event flow (outbox pattern)

There is no ledger/money-movement engine in this project (that's a different codebase's concern) —
the analogous "single source of truth" pattern here is the **outbox**:

```
Controller → @RequirePermission (AOP) → Service → Repository (TenantEntity @Filter)
                                             ↳ Outbox row (same transaction, HashMap payload)
                                                  ↳ RabbitMQ → integrations consumer → WhatsApp/FCM/SMS
```

Every tenant-scoped write and its outbox row commit together — no dual-write between DB state and
dispatched notifications.

## Liquibase Migration Convention

- Master file: `src/main/resources/db/changelog/db.changelog-master.yaml`
- Per-changeset file: `src/main/resources/db/changelog/NNN-<short-description>.sql` (flat directory,
  numbered globally — currently `001`–`019`)
- Forward-only: never write a `rollback:` that undoes a changeset already shipped to `main`
- New FKs to `users(id)`/`schools(id)` must use `ON DELETE CASCADE`, or existing test teardown
  (`deleteAll()`) breaks other tests that predate the new table (`docs/COMMON_MISTAKES.md` #8)
