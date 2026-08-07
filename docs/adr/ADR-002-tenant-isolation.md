# ADR-002: Tenant isolation via Hibernate `@Filter` + explicit `findById` override

**Status:** Accepted

## Context

SchoolBridge is multi-tenant (one tenant = one school) with a shared schema
(`school_id` column on every tenant-scoped table), not schema-per-tenant or
database-per-tenant. We need row isolation that's hard to forget and cheap
to verify.

## Decision

Every tenant-scoped entity extends `TenantEntity` and is scoped by a
Hibernate `@Filter`/`@FilterDef`, bound from `TenantContext` per request.
Because `@Filter` only applies to query paths (HQL/JPQL/criteria) and *not*
to `EntityManager.find()`, **every `TenantEntity` repository overrides
`findById` with an explicit JPQL `@Query`** so the primary-key lookup also
routes through the filtered query path.

`TenantEntityArchUnitTest` guards the inheritance side (entities that should
extend `TenantEntity` do). Each new tenant repo gets a matching
cross-tenant-invisibility integration test.

## Alternatives considered

- **Schema-per-tenant / DB-per-tenant** — stronger isolation, much higher
  operational cost for a many-small-schools product shape; rejected for now.
- **Hibernate native multi-tenancy (DISCRIMINATOR mode)** — would remove the
  `findById` override requirement entirely. Not adopted yet; if we switch to
  it later, the override becomes unnecessary boilerplate and can be
  retired module by module.
- **Explicit `school_id` predicate on every finder** (belt-and-suspenders on
  top of the filter) — flagged as a worthwhile hardening step
  (`docs/CODE_REVIEW.md` M2) but not yet required; current isolation tests
  cover the happy paths via the filter.

## Consequences

- The `findById` override is mandatory boilerplate on every new tenant repo
  — easy to forget, so it's the single most-repeated item in code review
  (`docs/CHECKLISTS.md`) and the top entry in `docs/COMMON_MISTAKES.md`.
- Isolation depends on the filter being active (bound tenant + active
  transaction). A finder called outside a transaction is a latent gap —
  known, accepted, tracked as M2.
