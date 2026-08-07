---
name: new-migration
description: Create a new Liquibase migration in SchoolBridge's actual format and register it in the master changelog.
argument-hint: <module>-<short-description> e.g. "homework-add-reminder-index"
allowed_tools: ["Bash", "Read", "Write", "Edit", "Grep", "Glob"]
---

# /new-migration $ARGUMENTS

## Steps

1. **Find the next number**: list
   `src/main/resources/db/changelog/*.sql` and take the highest existing
   3-digit prefix + 1. Numbering is global across all modules, sequential,
   never reused.

2. **Create** `src/main/resources/db/changelog/NNN-$ARGUMENTS.sql` in
   SchoolBridge's actual Liquibase-formatted-SQL style (match
   `015-authz.sql` exactly):

   ```sql
   --liquibase formatted sql

   --changeset schoolbridge:NNN-<short-id>
   --comment: <one line: what this does and why>
   CREATE TABLE ...;
   --rollback DROP TABLE ...;
   ```

   Rules:
   - One `--changeset schoolbridge:<id>` block per logical DDL unit; a file
     may contain several.
   - Every changeset needs a `--rollback` line (or block).
   - `String` fields → `VARCHAR(n)`, never `CHAR(n)` — `ddl-auto: validate`
     in prod compares types literally and `CHAR` blank-pads on read.
   - Money → `NUMERIC`; timestamps → `TIMESTAMPTZ`.
   - Any FK to `users(id)` or `schools(id)` → `ON DELETE CASCADE`
     (`docs/COMMON_MISTAKES.md` #8 — existing tests `deleteAll()` in
     `@BeforeEach` and will break otherwise).
   - A changeset containing `CREATE FUNCTION … $$ … $$` (plpgsql) needs
     `splitStatements:false` appended to its `--changeset` line and must
     contain *only* that function — Liquibase's formatted-SQL parser splits
     on `;` by default and will mangle the function body otherwise. Put any
     `CREATE TRIGGER` in a separate normal changeset in the same file.
   - Forward-only: never edit an already-applied changeset (one that's in
     `db.changelog-master.yaml` on `main`) — add a new one instead.

3. **Register** in
   `src/main/resources/db/changelog/db.changelog-master.yaml`, appended at
   the end (order is execution order):

   ```yaml
   - include:
       file: NNN-$ARGUMENTS.sql
       relativeToChangelogFile: true
   ```

4. If this adds a `TenantEntity`, use the **schoolbridge-tenant-entity**
   skill for the entity/repo side — the migration alone isn't enough.

5. **Verify**: `mvn -B -ntp -DskipTests compile` boots Liquibase against
   the Testcontainers Postgres and will fail loudly on a syntax error or a
   changeset ordering problem.
