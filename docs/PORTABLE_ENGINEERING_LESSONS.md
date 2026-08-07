# Portable Engineering Lessons

> **Provenance matters — read this first.** These are project-agnostic
> Spring/JPA/Liquibase/Maven lessons carried over from engineering work on
> a *different* codebase (an unrelated Spring-Modulith project). They are
> **not** verified SchoolBridge incidents — nothing here claims "this
> happened in SchoolBridge." Treat each as a pattern worth knowing, then
> verify against SchoolBridge's actual code before acting on it. For
> confirmed SchoolBridge incidents, see `docs/COMMON_MISTAKES.md` instead —
> don't conflate the two lists.

## Verify docs against the tree, not the other way around

**Rule**: Before stating any "fact" about the stack, modules, or layout
from a spec/plan doc, confirm it against the real source tree.
**Why**: Docs drift from code the moment someone changes the code without
updating the doc — and a wrong "fact" trusted at face value produces a
wrong plan. This exact principle is why the `wasla-*.md` files this lesson
came from were read in full, found to describe an unrelated project, and
never merged into SchoolBridge's docs.
**How to apply**: `grep`/`ls` the actual tree before trusting a doc's
specifics (module list, versions, entity fields). Applies to any inherited
doc, not just this one.

## `@Transactional` (or `@Async`/`@Cacheable`) does nothing on a same-bean self-call

**Rule**: A `@Transactional` method invoked as `this.method(...)` from
another method of the *same* class never passes through the Spring proxy —
the annotation is silently ignored. No compile error, no startup warning.
**Why**: The proxy wraps external calls to the bean, not internal ones.
**How to apply**: The tell is a `TransactionRequiredException`, or a write
that mysteriously doesn't roll back. Move the method to a separate bean, or
use `TransactionTemplate`, if a wrapper method must stay non-transactional
while calling a transactional step.

## Liquibase formatted-SQL splits on `;` — plpgsql function bodies need `splitStatements:false`

**Rule**: Any changeset containing `CREATE FUNCTION … $$ … $$` must declare
`splitStatements:false` on its `--changeset` line and contain *only* that
function; put `CREATE TRIGGER` statements in a separate normal changeset.
**Why**: Liquibase's formatted-SQL parser splits on `;` by default, tearing
a plpgsql body apart at its internal semicolons.
**How to apply**: SchoolBridge has no plpgsql functions/triggers yet — this
is forward-looking. If that ever changes, this is the first thing to get
right; there's no existing example in this repo to copy from.

## `CHAR(n)` fails `ddl-auto: validate` against a Java `String`

**Rule**: Use `VARCHAR(n)` in migrations for any column mapped to a
`String`, even fixed-width values like a hex digest.
**Why**: Hibernate maps `String` → `varchar`; schema validation compares
types literally, and `CHAR` also blank-pads on read, which breaks
string-equality checks. The failure is a global context-startup failure,
not local to the feature.
**How to apply**: Baked into the `/new-migration` command's rules. Fix the
migration, don't paper over with `columnDefinition` on the entity.

## A row lock in a batch-selection query protects nothing if each item settles in its own transaction

**Rule**: `SELECT ... FOR UPDATE SKIP LOCKED` in a driving/selection query
only works if the whole batch is processed inside that same transaction.
With per-item transactions (the usual shape for a sweep job), take the lock
in the *item's* transaction instead, next to a status re-check.
**Why**: A lock taken in the selection query is released when that
(read-only) transaction commits — before any per-item work runs — so it
excludes nothing.
**How to apply**: SchoolBridge's `HomeworkReminderSweeper` is the kind of
job shape this applies to (scan due items, act per item). Not a claim that
it has this bug — a reason to check the locking shape if you're ever
touching it or writing a similar sweeper (payment/attendance escalation,
etc.).

## JPA orders inserts before deletes — delete-then-insert on a unique key collides

**Rule**: When replacing a row under a unique constraint (find → delete →
save, in one transaction), call `repository.flush()` between the delete and
the insert, or use a `@Modifying` delete query with
`flushAutomatically = true`.
**Why**: Hibernate's action queue runs all inserts before all deletes
regardless of code order, so the insert can hit the unique constraint the
delete hasn't actually cleared yet.
**How to apply**: Any "replace by natural key" write path — look for a
unique constraint spanning a parent id plus a type/kind column.

## A derived `isX()`/`getX()` method on a record becomes a JSON field the constructor can't read back

**Rule**: `@JsonIgnore` every derived predicate method on a record/DTO that
crosses the wire — only the actual record components should appear in its
JSON.
**Why**: Jackson serializes `isZero()` as a `zero` field, then fails to
deserialize the same payload the record produced — "Unrecognized field."
Surfaces only on a round-trip (serialize → deserialize the same shape), so
a one-directional response test won't catch it.
**How to apply**: Any DTO/record in SchoolBridge with a helper predicate
method beyond its declared components. Prefer a round-trip assertion over a
serialize-only one when adding a test for such a type.

## A stale `test-compile` can report BUILD SUCCESS after a main-API change

**Rule**: After changing a signature, record, or enum in `src/main`, don't
trust a green `test-compile` alone — an incremental compiler check compares
test *sources* to test *classes* and can recompile nothing if no test
*source* changed, even though the classes are now stale against `src/main`.
**Why**: A false green here is worse than a red one — it invites running
the suite and blaming failures on logic instead of a stale compile.
**How to apply**: After a cross-cutting rename/retype, run the full
`mvn -B -ntp verify` (which does a real compile), not just a quick
incremental check, before trusting green.

## Webhook/callback dedupe: never audit-then-check in separate transactions

**Rule**: In a webhook handler, don't commit an "we received this event"
audit row in a separate (`REQUIRES_NEW`) transaction *before* the
duplicate-event check. Let dedupe be decided by a **unique constraint
violation inside the same transaction**, not by read-then-write.
**Why**: Under READ_COMMITTED, the duplicate-check query sees the audit row
the same request just committed, so the very first delivery misclassifies
itself as a duplicate and the real handling code becomes unreachable. It's
also a race: two concurrent deliveries can both pass a read-then-write
check.
**How to apply**: SchoolBridge's WhatsApp webhook
(`integrations/whatsapp/webhook`) and idempotency handling
(`common/idempotency`) are the places this class of bug would live if it
existed — worth a deliberate look if you're modifying either, not a claim
that it currently has this shape.
