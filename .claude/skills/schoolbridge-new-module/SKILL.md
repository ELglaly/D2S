---
name: schoolbridge-new-module
description: Scaffold a new SchoolBridge module (migration → entity → repo → DTO+mapper → service → controller → tests) following the gated build order. Use when starting the next module in Section 10's gate sequence (fees, messaging, reporting, audit, hardening) or any new module-shaped feature.
---

# SchoolBridge: new module scaffold

Before starting, confirm the previous module in the gate order
(`.claude/CLAUDE.md` → Gated Build Order) is green on
`mvn -B -ntp verify`. Never start a new module out of order.

## Steps (in this exact order — matches "Implementation Order" in CLAUDE.md)

1. **Migration first.** Add a Liquibase changeset under
   `src/main/resources/db/changelog/`, register it in
   `db.changelog-master.yaml`. Forward-only — never edit an already-applied
   changeset. Any FK to `users(id)` or `schools(id)` gets `ON DELETE CASCADE`
   (`docs/COMMON_MISTAKES.md` #8).

2. **Entity.** Package-by-feature: new module package at
   `com.schoolbridge.api.<module>`, entity class at the module root. If the
   entity is tenant-scoped, it extends
   `com.schoolbridge.api.common.tenancy.TenantEntity` — see the
   `schoolbridge-tenant-entity` skill before writing the repo.

3. **Repository.** `interface XRepository extends JpaRepository<X, UUID>`.
   If `X extends TenantEntity`, override `findById` with an explicit
   `@Query` (canonical example: `HomeworkItemRepository`). Add filtered/
   list-view finders as `@Query` methods with named params, matching the
   style in `HomeworkItemRepository`/`AttendanceRecordRepository`.

4. **DTO + mapper.** `dto/` sub-package: `CreateXRequest`,
   `UpdateXRequest`, `XResponse`, `XMapper`. Request DTOs carry jakarta
   validation constraints (`@NotNull`, `@Size`, …) — see `homework/dto` for
   the shape.

5. **Service.** Interface + `*Impl` split (`XService`/`XServiceImpl`) so
   tests can mock without touching final classes. Any cross-module
   notification goes through the outbox
   (`OutboxEventRecorder.record(...)`, payload built with `HashMap` — never
   `Map.of(...)`, see `docs/COMMON_MISTAKES.md` #3), not a direct call into
   `integrations`.

6. **Controller.** Slash-style paths only (`/module/action`, never
   `/module:action` — `docs/adr/ADR-006-slash-style-action-paths.md`).
   Gate with `@RequirePermission`; add row-ownership checks as a trimmed
   `@PreAuthorize("@perms....")` alongside it if the endpoint needs one
   (`docs/adr/ADR-003-rbac-aop-permissions.md`). New `Permission` enum
   values go in `common/security/authz/Permission.java`.

7. **i18n.** Any new user-facing message goes in **both**
   `messages_en.properties` and `messages_ar.properties` — use the
   `schoolbridge-i18n-message` skill.

8. **Tests.** Unit tests for the service, integration tests for the
   controller (MockMvc/REST Assured) including a cross-tenant isolation
   test if the entity is tenant-scoped, and an authorization test
   (unauthenticated → 401, wrong role → 403) mirroring
   `GradesAuthorizationIntegrationTest`.

9. **Gate.** `mvn spotless:apply` then `mvn -B -ntp verify` green before
   the module is considered done. Update the OpenAPI docs
   (`docs/api/openapi.{json,yaml}`) if the controller surface changed.

## Common pitfalls

Read `docs/COMMON_MISTAKES.md` in full before starting — items #1, #3, #8,
#9, #10 are the ones most likely to bite a new module specifically.

## If this is an AI-assistant-facing capability too

Don't scaffold the tool in the same pass as the service — land the service
+ controller, get it green, *then* add the assistant tool as a follow-up
using the `schoolbridge-assistant-tool` skill so the tool can call the
already-tested service.
