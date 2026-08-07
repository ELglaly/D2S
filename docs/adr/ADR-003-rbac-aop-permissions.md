# ADR-003: `@RequirePermission` + AOP, DB-backed single-role permission model

**Status:** Accepted

## Context

The original authorization was `@PreAuthorize("hasRole(...)")` scattered
across controllers, doing two jobs at once: coarse role gating and
fine-grained row-ownership checks (`@perms.teacherTeaches`,
`@perms.parentLinkedTo`, etc.). Role-based gating alone doesn't allow
runtime-editable, per-permission grants without a redeploy.

## Decision

Add a DB-backed, runtime-editable permission layer:

- Global `permissions` catalog (`Permission` enum mirrors DB rows by name)
  and `role_permissions(role, permission_id)` mapping.
- **Not** the textbook `users/roles/user_roles` many-to-many schema —
  SchoolBridge is stateless-JWT with a **single `UserRole` per principal**
  (`SUPER_ADMIN`/`SCHOOL_ADMIN`/`TEACHER`/`PARENT`, carried as a JWT claim).
  So the effective-permissions cache is keyed by **role** (≤4 keys), not by
  user.
- `@RequirePermission(Permission[] value, Mode ALL|ANY)` + a Spring AOP
  `@Around` aspect enforces the coarse gate; the aspect throws
  `AccessDeniedException`, which `GlobalExceptionHandler` already maps to
  RFC 7807 403 — no new exception type needed.
- `@RequirePermission` replaces **only** the coarse role-gate job.
  Row-ownership checks (`@perms.*`) are preserved as a trimmed
  `@PreAuthorize` alongside the aspect — deleting them would open row-level
  access holes.
- `EffectivePermissionService` is `@Cacheable` via an explicit
  `CaffeineCacheManager` bean (not the auto-configured Redis manager) so
  authorization survives a Redis outage.
- `PermissionCatalogReconciler` inserts any `Permission` enum value missing
  from the DB on startup (insert-only, race-safe, no auto-grant to any
  role — new permissions default to nobody).

## Consequences

- Migrating a controller is mechanical but has three distinct shapes:
  pure-role → replace with `@RequirePermission`, drop `@PreAuthorize`;
  role+ownership → add `@RequirePermission` coarse gate, **keep** the
  trimmed `@PreAuthorize` body unchanged; parent-inclusive reads where the
  parent lacks the coarse permission → leave as `@PreAuthorize`-only.
- Cache staleness up to ~10 minutes on a permission revoke in a multi-
  instance deployment (Caffeine is per-JVM); multi-instance invalidation via
  Redis pub/sub is deferred, not blocking for the current deployment shape.
- New permission values are secure-by-default (nobody granted) until a
  migration or admin action grants them to a role.
