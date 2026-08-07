---
name: schoolbridge-tenant-entity
description: Create a new tenant-scoped entity + repository in SchoolBridge without the Hibernate @Filter/findById isolation bypass. Use whenever adding an entity that belongs to a school (i.e. has a school_id column).
---

# SchoolBridge: safe tenant-scoped entity + repository

Read `docs/adr/ADR-002-tenant-isolation.md` and
`docs/COMMON_MISTAKES.md` #1 first if you haven't touched a tenant repo
before — this is the single most-repeated gotcha in the codebase.

## Why this matters

Hibernate `@Filter` only applies to query paths (HQL/JPQL/criteria). Spring
Data's default `findById` calls `EntityManager.find()`, which bypasses the
filter entirely. An un-overridden `findById` on a tenant entity lets a
caller in one school load a row from another school by ID.

## Steps

1. **Entity** extends `com.schoolbridge.api.common.tenancy.TenantEntity`
   (carries `schoolId`). `TenantEntityArchUnitTest` will flag entities that
   should extend it but don't.

2. **Repository** — override `findById` with an explicit `@Query`:

   ```java
   public interface XRepository extends JpaRepository<X, UUID> {

     @Override
     @Query("select x from X x where x.id = :id")
     Optional<X> findById(@Param("id") UUID id);

     // ... other finders, all as @Query with named params
   }
   ```

   `HomeworkItemRepository` and `UserRepository` are the canonical
   examples — match their style (JPQL string concatenation for multi-line
   queries, `@Param` on every bind variable).

3. **Isolation test** — add a cross-tenant-invisibility test: create rows in
   two schools, bind `TenantContext` to school A, assert `findById`/
   `findAll`/every custom finder cannot see school B's row. Mirror
   `UserRepositoryIsolationTest.findById_underTenantA_cannotSeeUserInB`.

4. **Known residual risk (not something to "fix" reflexively):** isolation
   depends on the filter being *active* — a bound tenant and an active
   transaction. A finder called outside a transaction context is a latent
   gap (`docs/CODE_REVIEW.md` M2). If you're touching code that calls a
   tenant repo finder outside the normal controller→service→repo
   transactional path, flag it rather than assuming the filter protects you.

## Checklist before moving on

- [ ] Entity extends `TenantEntity`
- [ ] `findById` overridden with `@Query`
- [ ] Every custom finder is a `@Query`, not a derived-name method that
      could silently route around the filter
- [ ] Cross-tenant isolation test added and passing
- [ ] FK to `schools(id)` (and `users(id)` if applicable) has
      `ON DELETE CASCADE`
