  ---
name: jpa-patterns
description: JPA/Hibernate patterns and pitfalls (N+1, lazy loading, transactions) grounded in SchoolBridge's actual repository style. Use when diagnosing LazyInitializationException, too-many-queries, or designing a new query.
---

# JPA Patterns (SchoolBridge)

SchoolBridge's house style is **named-parameter `@Query` (JPQL) for anything
beyond a trivial derived-name finder** — see `HomeworkItemRepository` as the
reference. This skill covers the pitfalls that style avoids by construction,
plus the ones it doesn't.

## N+1 queries

The #1 JPA performance killer: `findAll()` then accessing a lazy
`@OneToMany`/`@ManyToOne` per row triggers one query per row.

```java
// BAD: 1 + N queries
List<HomeworkItem> items = repo.findAll();
items.forEach(i -> i.getRecipients().size());  // N extra SELECTs
```

Fix with `JOIN FETCH` in the `@Query`, not `@EntityGraph` — stay consistent
with the codebase's explicit-JPQL style:

```java
@Query("select h from HomeworkItem h join fetch h.recipients where h.id = :id")
Optional<HomeworkItem> findByIdWithRecipients(@Param("id") UUID id);
```

Detect it by enabling SQL logging locally:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        format_sql: true
logging:
  level:
    org.hibernate.SQL: DEBUG
```

## Lazy loading defaults

- `@OneToMany`/`@ManyToMany` default to `LAZY` — leave them.
- `@ManyToOne`/`@OneToOne` default to `EAGER` — **override to `LAZY`
  explicitly** unless the association is genuinely always needed with the
  owning row.
- Cross-module references in SchoolBridge are stored as raw foreign IDs
  (`UUID classId`, `UUID teacherId` — see `HomeworkItem`), not JPA
  associations at all. This sidesteps lazy-loading pitfalls entirely for
  cross-module data — keep doing this rather than introducing a
  `@ManyToOne` across module boundaries.

## `LazyInitializationException`

Happens when a lazy field is accessed after the transaction (and Hibernate
session) has closed — typically: service method returns an entity, a mapper
or controller touches a lazy collection outside `@Transactional`.

Fix at the query (`join fetch` the association you need), not by widening
the transaction boundary into the web layer — SchoolBridge doesn't use
Open-Session-in-View.

## Transactions

- Service methods are `@Transactional` for writes;
  `@Transactional(readOnly = true)` for reads that traverse any lazy field —
  this also lets Hibernate skip dirty-checking on the loaded entities.
- **`@Transactional` on a method called via `this.method(...)` from another
  method of the same bean does nothing** — the call bypasses the Spring
  proxy entirely, silently. No compile error, no startup warning. The tell
  is a `TransactionRequiredException` or a write that doesn't roll back on
  failure. Move the method to a separate bean, or use `TransactionTemplate`,
  if a wrapper method must stay non-transactional while calling a
  transactional step.

## Optimistic locking

For any entity multiple actors can update concurrently (attendance records,
homework recipient status), consider `@Version` before reaching for
`@Lock(PESSIMISTIC_WRITE)` — cheaper and sufficient unless the contention is
a tight per-row race (a batch job settling many rows is the case where a
pessimistic row lock in the *per-item* transaction, not the driving
selection query, is correct — see `docs/PORTABLE_ENGINEERING_LESSONS.md`).

## Schema validation (`ddl-auto: validate`)

Prod runs `ddl-auto: validate` — Hibernate compares Java types to the
Liquibase-created columns literally. A `String` field must map to
`varchar`, never `char`/`bpchar`, even for fixed-width values like a hex
digest — `CHAR(n)` blank-pads on read, which also breaks string-equality
checks. Get the column type right in the migration; don't paper over a
mismatch with `columnDefinition` on the entity.
