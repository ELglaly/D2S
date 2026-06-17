# Plan: Permission-Based Authorization via Spring AOP

> Committed plan doc. Drives the multi-phase RBAC/AOP work. Not auto-committed to git.

## Goal

Add a DB-backed, runtime-editable permission layer enforced by a custom
`@RequirePermission` annotation + a Spring AOP aspect, then migrate the existing
`@PreAuthorize("hasRole(...)")` controller gates onto it. Admin endpoints manage
role→permission assignments, themselves protected by `MANAGE_ROLES` /
`MANAGE_PERMISSIONS`.

## Adaptation to SchoolBridge reality (NOT the textbook schema)

The generic RBAC tutorial assumes `users ⇄ roles ⇄ permissions` M2M tables.
SchoolBridge already has:

- **Stateless JWT auth.** Authorities built in `BearerAuthenticationFilter` from
  JWT claims. No DB session.
- **Single role per principal** — `UserRole` enum `{SUPER_ADMIN, SCHOOL_ADMIN,
  TEACHER, PARENT}`, carried in the JWT (`role` claim) / principal record.
- `GlobalExceptionHandler` already maps Spring `AccessDeniedException` → RFC7807
  **403** (`ErrorType.AUTHORIZATION`) and Spring `AuthenticationException` → **401**.

So we DO NOT add `users`/`roles`/`user_roles`. Instead:

- "Role" = the existing `UserRole` enum (4 fixed values).
- M2M is **`role_permissions(role, permission_id)`** + a global **`permissions`**
  catalog (not tenant-scoped — same names across all schools).
- A user's effective permissions = the permissions mapped to their single role.
- **Cache keyed by role** (≤4 keys), not by user.
- Aspect resolves the role from the `ROLE_*` authority already on the
  `Authentication` (principal-type-agnostic).
- Aspect throws Spring `AccessDeniedException` → existing handler → 403. No new
  exception class, no new i18n key.

## Two jobs of the current `@PreAuthorize` — keep them separate

1. **Coarse role/permission gate** (`hasRole`, `hasAnyRole`) → replaced by
   `@RequirePermission`.
2. **Row-level ownership** (`@perms.teacherTeaches(#classId)`,
   `@perms.isHomeworkAuthor(#id)`, `@perms.parentLinkedTo(#studentId)`, …) →
   a static permission name CANNOT express "does THIS teacher own THIS row".
   **These `@perms` checks MUST be preserved.** Migration keeps them (as a
   trimmed `@PreAuthorize` alongside `@RequirePermission`, or moved into a
   service-layer ownership guard). Deleting them opens row-level access holes.

## Phases (each ends green: `mvn -B -ntp verify`)

### Phase 1 — Foundation (this change)
- pom: `spring-boot-starter-cache` + `caffeine`.
- `com.schoolbridge.api.common.security.authz`:
  - `Permission` enum (code catalog).
  - `RequirePermission` annotation (`Permission[] value()`, `Mode {ALL, ANY}`,
    `@Target METHOD+TYPE`).
  - `PermissionEntity`, `RolePermission` JPA entities (global, non-tenant).
  - `PermissionRepository`, `RolePermissionRepository` (single-join name query —
    no N+1).
  - `EffectivePermissionService` — `@Cacheable("rolePermissions", key=#role)`,
    `@CacheEvict` per-role + all.
  - `AuthzCacheConfig` — `@EnableCaching` + explicit `CaffeineCacheManager` bean
    (so Boot does NOT auto-pick the Redis cache manager → authz survives Redis
    outage; 10-min `expireAfterWrite` safety net + explicit evict).
  - `PermissionAspect` — `@Around` on `@annotation` / `@within`
    `RequirePermission`; unauth → 401, missing perm → 403.
- Liquibase `015-authz.sql` — `permissions` + `role_permissions` + seeds.
  `MANAGE_ROLES`/`MANAGE_PERMISSIONS` seeded to **SUPER_ADMIN only**.
- Admin endpoints `RolePermissionAdminController` + `RolePermissionAdminService`
  under `/api/v1/admin/authz`, gated by `@RequirePermission`, evict-on-write.
- Unit test for the aspect (ALL/ANY/deny/unauth).

### Phase 2 — Migrate controllers (next change, module-by-module per gate order)
Per-endpoint table built from the ~60 `@PreAuthorize` sites:
- pure role check → swap to `@RequirePermission(...)`.
- role + `@perms.*` → add `@RequirePermission(...)` for the coarse gate, KEEP the
  `@perms.*` ownership check.
- Bridge: principals can keep `ROLE_*` authority; aspect reads role from it, so
  both annotations coexist during incremental migration.
Verify green after each module before the next.

### Phase 3 — Cleanup / hardening
- Decide whether to drop pure-role `@PreAuthorize` entirely (keep ownership-only).
- Optional: startup reconciler inserting any `Permission` enum value missing from
  the catalog (grant() already self-heals).
- Optional: multi-instance cache invalidation via Redis pub/sub if the per-JVM
  Caffeine staleness window (≤10 min) is unacceptable.

## Cache invalidation
| Admin action | Evict |
|---|---|
| grant/revoke permission on a role | `evictRole(role)` |
| bulk reset | `evictAll()` |

Per-JVM Caffeine → on multi-instance, other JVMs converge within the 10-min TTL.
