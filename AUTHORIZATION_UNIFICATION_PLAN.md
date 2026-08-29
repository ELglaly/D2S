# Unify authorization around `@RequirePermission`

## Summary

The project already contains the intended permission abstraction:

```text
@RequirePermission(Permission.X)
```

implemented by `PermissionAspect`, backed by `Permission`, `permissions`, and `role_permissions`.

The migration strategy is:

```text
@RequirePermission → static business capability
Service authorization policy → resource ownership and row-level rules
```

All application `@PreAuthorize` role/authority gates will be removed. Resource-specific rules such as teacher ownership and parent/student linkage will move into named service-level authorization policies that do not inspect roles directly.

## Implementation Changes

- Expand and normalize `Permission` around actual project operations: grades, homework, attendance, classes, subjects, enrollment, students, parent links, announcements, attachments, assistant settings/documents, users, schools, diagnostics, and authorization administration.
- Preserve existing access behavior from current role rules and database grants.
- Add only permissions required by real endpoints; remove obsolete constants after usages are migrated.
- Add forward-only Liquibase changesets for new permission catalog entries and role grants.
- Convert every protected controller/service operation from role expressions to explicit `@RequirePermission`.
- Choose permissions from the operation: create → `*_CREATE`, read/list/get → `*_READ`, update/patch → `*_UPDATE` or an explicit manage permission, delete → `*_DELETE`, and publish/acknowledge/record → the corresponding action permission.
- Move ownership checks into reusable service authorization policies:
  - teacher teaches class/subject
  - teacher owns homework/announcement/attachment
  - parent is linked to student
  - parent received announcement/homework
  - parent may access an attached resource
- Ensure policies use trusted authenticated principals and domain relationships, never client-supplied role values.
- Remove direct role checks from business authorization paths, including role branching in `PermissionsHelper` where it represents authorization.
- Keep role comparisons needed for identity modeling, persistence validation, display, token issuance, assistant UX, or domain classification, and document each remaining occurrence.
- Apply permissions at service boundaries for reusable business operations. Keep controller annotations only where protection is specifically HTTP-entry-point-related.
- Remove unused `@PreAuthorize` imports, SpEL authorization helpers, and obsolete security utilities.
- Keep `PermissionAspect` as the single application authorization enforcement mechanism.
- Remove `@EnableMethodSecurity` only after confirming no Spring method-security annotations or infrastructure remain.

## Permission Inventory

Create a complete table covering all protected endpoints and service methods:

| Module | Endpoint or method | HTTP method | Current authorization | Current roles | Required permission | New annotation | Changes required |
|---|---|---|---|---|---|---|---|
| Grades | All protected operations | POST/GET/PATCH/DELETE | Existing role rules | Existing role grants | Grade operation permission | `@RequirePermission` | Add service policies where ownership applies |
| Homework | All protected operations | POST/GET/PATCH/DELETE | Existing role rules | Existing role grants | Homework operation permission | `@RequirePermission` | Add author and recipient policies |
| Attendance | All protected operations | GET/POST | Existing role rules | Existing role grants | Attendance read/record | `@RequirePermission` | Add class and parent-link policies |
| Classes, subjects, enrollment, students | All protected operations | GET/POST/PATCH/DELETE | Existing role rules | Existing role grants | Domain operation permission | `@RequirePermission` | Add teacher/parent relationship policies |
| Announcements | All protected operations | POST/GET | Existing role rules | Existing role grants | Announcement operation permission | `@RequirePermission` | Add sender and recipient policies |
| Attachments | All protected operations | POST/GET/DELETE | Existing role rules | Existing role grants | Attachment operation permission | `@RequirePermission` | Add uploader and recipient policies |
| Identity/users, schools, diagnostics | All protected operations | GET/POST/PATCH/DELETE | Existing role rules | Existing role grants | Administration permission | `@RequirePermission` | Remove direct role gates |
| Assistant settings/documents | All protected operations | GET/POST/PATCH/DELETE | Existing role rules | Existing role grants | Assistant administration permission | `@RequirePermission` | Enforce at reusable service boundary |
| Authorization administration | Role/permission mapping operations | GET/POST/PATCH/DELETE | `SUPER_ADMIN` role gate | `SUPER_ADMIN` | `MANAGE_ROLES`, `MANAGE_PERMISSIONS` | `@RequirePermission` | Preserve super-admin-only access |

## Role Mapping

Preserve effective access first, then express it as role groups:

```text
SUPER_ADMIN  → all currently granted permissions
SCHOOL_ADMIN → current school-administration permissions
TEACHER      → current instructional/read/write permissions
PARENT       → current parent-facing read/acknowledgement permissions
```

The exact mapping will be generated from the existing `015-authz.sql` and `018-attachments.sql` grants plus newly required operation permissions. `MANAGE_ROLES` and `MANAGE_PERMISSIONS` remain restricted to `SUPER_ADMIN`.

## Test Plan

- `PermissionAspect`: unauthenticated/anonymous → 401, missing permission → 403, granted permission → allowed, `ALL`/`ANY` modes, and unknown role authority → denied.
- Role mapping: each role receives intended permissions; ungranted permissions remain denied.
- Endpoint permissions: create, read, update, delete, publish, acknowledge, and record operations.
- Resource policies: teacher ownership, parent linkage/recipient rules, and attachment ownership/access.
- Integration status codes: no bearer token → 401; valid token without permission → 403; valid token with permission reaches normal business response.
- Authentication compatibility: staff JWT role maps to trusted authority; parent opaque authentication receives parent permissions; invalid/expired credentials remain 401.
- Final searches verify no unreviewed `@PreAuthorize`, direct `hasRole`/`hasAnyRole`/authority expressions, or undocumented role-based business authorization.

## Assumptions

- `@RequirePermission` remains the sole application authorization abstraction.
- JWT architecture remains unchanged: roles identify the authenticated principal; permissions are server-side DB-derived.
- Existing access is preserved unless a rule is demonstrably inconsistent or insecure.
- Resource ownership is separate from coarse capability permission and is implemented at service boundaries without role checks.
- Database migrations are forward-only and existing production data remains compatible.
