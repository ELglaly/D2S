# SchoolBridge — Module Catalog

Authoritative list of the 14 modules, their sub-packages, and dependency rules.
Base package: `com.schoolbridge.api`. Package-by-feature, not package-by-layer — a module's entity,
repository, service, and controller live at the module root; `dto/` is a sub-package. Every service
uses the interface + `*Impl` split (`HomeworkService` / `HomeworkServiceImpl`) so tests can mock
without touching final classes.

## Dependency Rules

1. Never point backward against the build order below (e.g. `attendance` must not depend on
   `homework`).
2. `common` and `config` are depended on by everything and depend on nothing module-specific.
3. `assistant` depends on the domain modules' **services**, never repositories directly — a tool call
   and a REST call must go through the same authorization and business-rule path (ADR-005).
4. `integrations` is the only module allowed to talk to external systems (WhatsApp, FCM, SMS). Other
   modules publish an **outbox row in the same transaction** as the domain write; a RabbitMQ consumer
   in `integrations` picks it up asynchronously. This is the no-dual-write seam — see
   `docs/COMMON_MISTAKES.md` #3 for the `Map.of(...)` trap in outbox payloads.
5. There is no compile-time module-boundary enforcement (no Spring Modulith, no package-private
   `internal/`) — `TenantEntityArchUnitTest` enforces the tenancy convention, not module isolation.
   Cross-module entity references still go through a foreign id field, by convention, not a compiler
   check.

## Build/Dependency Order

```
tenant → identity → classes → announcements → integrations → attendance → homework
      → attachments → notifications → assistant
```

All 14 modules are built (see `docs/HANDOFF_M5.md`–`docs/HANDOFF_M9.md`); the project is currently in
the **P0 pre-launch remediation** phase (`docs/P0_REMEDIATION.md`), not further module gating.

## The 14 Modules

### `common`
Shared base entities and cross-cutting infrastructure every module depends on.
**Sub-packages**: `audit`, `crypto` (AES-GCM + blind index for deterministic-lookup PII), `error`
(`ErrorType`, exception mapping), `i18n`, `idempotency`, `persistence` (`TenantEntity` base),
`security` (JWT, `@RequirePermission` + AOP aspect, `authz/Permission`,
`authz/RolePermissionAdminController`), `tenancy` (tenant filter/context), `web` (`ApiResponse`
envelope, `ApiResponseBodyAdvice`), `outbox` (`OutboxEventRecorder`, `OutboxStatus`).

### `config`
`ApplicationConfig`, `OpenApiConfig` — cross-cutting Spring `@Configuration` beans. No domain logic.

### `tenant`
School onboarding and tenant resolution. **Entity**: school record with `SchoolStatus`,
`SubscriptionTier`, default `Language`. **Controller**: `SchoolController`
(`/api/v1/schools` — create, list, get, settings get/put, suspend, reactivate).

### `identity`
Users, roles, JWT auth, refresh tokens, device tokens, OTP, platform admin.
**Sub-packages**: `auth` (`AuthController` — login/refresh/logout; `ParentAuthController` —
OTP-based parent login), `device` (`DeviceController`, `DevicePlatform`).
**Entities/enums**: `User`, `UserRole` (`SUPER_ADMIN`, `SCHOOL_ADMIN`, `TEACHER`, `PARENT` —
single-role model, not a set), `UserStatus` (`ACTIVE`, `SUSPENDED`), `SubjectKind` (`USER`,
`PLATFORM_ADMIN` — distinguishes a tenant `SUPER_ADMIN` from the cross-tenant `PlatformAdmin`).

### `classes`
Classrooms, students, enrollments, parent-child links.
**Controllers**: `SchoolClassController`, `StudentController`, `EnrollmentController`,
`ParentChildrenController`, `ParentStudentLinkController`.
**Entities/enums**: `SchoolClass` (avoid the bare word "class" elsewhere — reserved word), `Student`
(`StudentStatus`: `ACTIVE`, `INACTIVE`, `GRADUATED`, `SUSPENDED` — distinct entity from `User`, a
student may have no login), `Enrollment`, `ParentStudentLink` (`RelationshipType`: `MOTHER`,
`FATHER`, `GUARDIAN`). Most parent-facing reads resolve "my child" through `ParentStudentLink`, never
a bare student id from the caller.
> `StudentController`'s bulk-import endpoint is still `:bulk-import` (colon-verb) — a known,
> not-yet-fixed instance of the ADR-006 trap. Don't copy that pattern into new endpoints.

### `subjects`
Per-school subject catalog. **Controllers**: `SubjectController`, `ClassSubjectController`,
`TeacherSubjectAssignmentController`. **Enum**: `SubjectStatus` (`ACTIVE`, `INACTIVE`).

### `grades`
Grade records. **Controller**: `GradesController`, gated by `GRADE_*` permissions.

### `announcements`
School/class announcements, targeting, acknowledgement, recall.
**Enums**: `AnnouncementStatus` (`DRAFT`, `SCHEDULED`, `SENDING`, `SENT`, `RECALLED`),
`AnnouncementScope` (`SCHOOL`, `GRADE`, `CLASS`, `CUSTOM`), `DeliveryStatus`
(`QUEUED`, `DEFERRED`, …). Recipient fan-out is materialized at send time —
`AnnouncementServiceImpl.materializeRecipients` is the canonical batched-`saveAll` pattern other
fan-out code (homework reminders, attendance alerts) follows.

### `attendance`
Attendance records, absence alerts, reports. **Enums**: `AttendanceStatus` (`PRESENT`, `ABSENT`,
`LATE`, `EXCUSED`), `AttendanceAlertStatus` (`PENDING`, `DEFERRED`, `SENT`, `FAILED`).
`AttendanceAlertService` + a scheduled sweeper are the canonical quiet-hours-aware fan-out pattern.

### `homework`
Homework items, per-parent recipients, reminders. **Enums**: `HomeworkStatus` (`DRAFT`,
`PUBLISHED`, `ARCHIVED` — only `PUBLISHED` is visible to parents/students and eligible for reminder
sweeps), `HomeworkDeliveryStatus` (`PENDING`, `DEFERRED`, `SUPPRESSED`, `SENT`, `FAILED`).

### `attachments`
Presigned S3-compatible upload/download, MIME sniffing, AV scan, retention sweep.
**Enum**: `AttachmentStatus` (`PENDING` → `UPLOADED` → `CLEAN`, or `REJECTED`/`INFECTED`).
`PENDING` means an upload URL was issued but the API is never told the client's PUT landed — a
sweeper deletes long-abandoned `PENDING` rows rather than trying to reconcile them. Also holds
`AvResult` (`CLEAN`, `INFECTED`, `SKIPPED`).

### `notifications`
Per-user quiet hours, per-category opt-out, channel order — **preferences only**, not dispatch.
**Controller**: `NotificationPreferenceController`. **Enum**: `NotificationCategory`
(`ANNOUNCEMENT`, `HOMEWORK`, `ATTENDANCE`).

### `integrations`
WhatsApp (Meta Cloud API) / push (FCM) / SMS adapters, RabbitMQ outbox consumers. The only module
allowed to reach an external system. **Controllers**: `IntegrationsWhatsAppWebhookController`
(inbound webhook, HMAC-verified via `WebhookSignatureVerifier`), `WhatsAppDiagnosticsController`.
**Enum**: `NotificationChannel` (`PUSH`, `WHATSAPP`, `SMS`). `NotificationDispatcher` walks the
channel list and stops at the first channel that **accepts** — a stub/no-op channel must report
failure, or it silently swallows every notification behind it (`docs/COMMON_MISTAKES.md` #15).

### `assistant`
AI assistant: conversation, tool-calling, RAG, permission-gated actions. **Ships dark**
(`ASSISTANT_ENABLED=false` by default).
**Sub-packages**: `audit`, `cache`, `confirm` (`ConfirmIntent.Decision` — destructive-action
confirmation flow), `dto`, `llm` (+ `llm/springai` provider adapter), `rag` (`DocType`,
`IngestStatus`, pgvector-backed retrieval, `DocumentAdminController`), `settings`
(`AssistantSettingsController`), `conversation` (`ConversationController`,
`ConversationMessageController`, `MessageRole`), and `tools/<domain>` — one package per business
domain (`announcements`, `attendance`, `classes`, `grades`, `homework`, `parents`, `read`, `staff`,
`student`, `subjects`, `support`, `action`). Each tool is a thin adapter over an existing service —
see `docs/adr/ADR-005-assistant-tool-architecture.md`. `ToolDomain` gates which tools are offered for
a given query instead of advertising the caller's entire permission catalog (dominant input-token
cost); `ToolKind` is `READ` vs `ACTION` — `ACTION` tools may require confirmation.

## Authorization Model

`Permission` enum (`common/security/authz/Permission`) — fine-grained, enforced via
`@RequirePermission` + AOP: `GRADE_{CREATE,READ,UPDATE,DELETE}`, `HOMEWORK_{CREATE,PUBLISH,READ,
UPDATE,DELETE,ACK}`, `ATTENDANCE_{RECORD,READ}`, `CLASS_{MANAGE,READ}`, `SUBJECT_{MANAGE,READ}`,
`ENROLLMENT_MANAGE`, `STUDENT_{MANAGE,READ}`, `PARENT_LINK_MANAGE`, `ANNOUNCEMENT_{SEND,READ,
MANAGE}`, `ATTACHMENT_{UPLOAD,READ,DELETE}`, `ASSISTANT_SETTINGS_MANAGE`, `DOCUMENT_MANAGE`,
`USER_{READ,MANAGE}`, `SCHOOL_MANAGE`, `WHATSAPP_DIAGNOSTICS`, `MANAGE_ROLES`. `role_permissions`
maps roles → permissions at runtime with a role-keyed cache; `MANAGE_ROLES`/`MANAGE_PERMISSIONS` are
seeded to `SUPER_ADMIN` only. Row-ownership checks ("this parent owns this child", "this teacher
teaches this class") are narrower than a permission and stay as `@PreAuthorize` alongside the aspect,
not folded into `Permission` itself.

## Naming Conventions

- Module package: `com.schoolbridge.api.<module>` (singular except `announcements`/`notifications`
  which follow their real-world plural name)
- REST controllers: `<Feature>Controller`; class names always end in `Controller`
- Services: `<Feature>Service` interface + `<Feature>ServiceImpl`
- Enums live at the module root, not nested inside an unrelated class, unless scoped to one DTO
  (e.g. `ConfirmIntent.Decision`)
- Liquibase changeset files: `NNN-<module>-<short-description>.sql` (flat files, numbered globally —
  see `db/changelog/`)

## Verification

`TenantEntityArchUnitTest` (`src/test/java/com/schoolbridge/api/architecture/`) enforces that
tenant-scoped entities carry the `@Filter` annotation. There is no module-boundary structure test —
module separation here is a naming/package convention, not a compiler-enforced one.
