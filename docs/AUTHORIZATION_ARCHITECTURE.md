# Authorization architecture

## Enforcement model

HTTP and reusable business operations declare static capabilities with RequirePermission(Permission.X). PermissionAspect authenticates the caller, extracts the trusted role authority issued by the existing staff JWT or parent opaque-token authentication, and resolves permissions from permissions and role_permissions.

Resource authorization is separate. AuthorizationPolicy and its relationship methods evaluate authenticated principal IDs against tenant-scoped domain relationships. Client-provided roles, permissions, or ownership claims are not trusted.

## Endpoint matrix

| Module | Operation family | Capability |
|---|---|---|
| Grades | create/read/update/delete | GRADE_CREATE/READ/UPDATE/DELETE |
| Homework | create/read/update/delete/publish/acknowledge | HOMEWORK_CREATE/READ/UPDATE/DELETE/PUBLISH/ACK |
| Attendance | read/record/parent response | ATTENDANCE_READ/RECORD/RESPOND |
| Classes | administration and teacher reads | CLASS_MANAGE/READ |
| Subjects | administration and reads | SUBJECT_MANAGE/READ |
| Enrollment | all operations | ENROLLMENT_MANAGE |
| Students | administration and parent reads | STUDENT_MANAGE/READ |
| Parent links | all operations | PARENT_LINK_MANAGE |
| Announcements | send/read/manage | ANNOUNCEMENT_SEND/READ/MANAGE |
| Attachments | upload/read/delete | ATTACHMENT_UPLOAD/READ/DELETE |
| Assistant settings | all operations | ASSISTANT_SETTINGS_MANAGE |
| Assistant documents | all operations | DOCUMENT_MANAGE |
| Users | read/manage | USER_READ/MANAGE |
| Schools | administration | SCHOOL_MANAGE |
| WhatsApp diagnostics | diagnostics | WHATSAPP_DIAGNOSTICS |
| Authorization administration | role/permission mappings | MANAGE_ROLES/MANAGE_PERMISSIONS |

## Role grants

- SUPER_ADMIN: all catalog permissions.
- SCHOOL_ADMIN: school administration and management permissions seeded by the existing authz changelog.
- TEACHER: instructional read/write permissions, including grade deletion retained by the normalization migration.
- PARENT: parent-facing reads and acknowledgements, including linked student, subject, attendance, and attachment reads.

## Intentional role references

Roles remain in the authentication model and are used only to identify the trusted principal for server-side permission lookup and identity/token behavior. Business endpoints no longer use Spring role-expression annotations.

