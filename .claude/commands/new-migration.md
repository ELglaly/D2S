---
description: Create a new Liquibase SQL migration file for SchoolBridge and register it in the master changelog.
argument-hint: <description> e.g. "fees-catalog" or "homework-add-priority-index"
---

Create a new Liquibase migration for the SchoolBridge project.

Migration description: $ARGUMENTS

Steps:

1. **Find the next migration number**:
   - Check the tail of `src/main/resources/db/changelog/db.changelog-master.yaml` for the last
     included file
   - The next file is `src/main/resources/db/changelog/NNN-<description>.sql`, where `NNN` is the
     next sequential number, **global across all modules** — not per-module

2. **Create the SQL file** at `src/main/resources/db/changelog/NNN-<description>.sql`, following the
   real format used throughout the project (see `008-device-tokens.sql` for the canonical example):

   ```sql
   --liquibase formatted sql

   --changeset schoolbridge:NNN-<description>
   --comment: <what this table/column is for and why>
   CREATE TABLE <table> (
       id          UUID         PRIMARY KEY,
       school_id   UUID         NOT NULL REFERENCES schools(id) ON DELETE CASCADE,
       ...
       created_at  TIMESTAMPTZ  NOT NULL,
       updated_at  TIMESTAMPTZ  NOT NULL
   );

   CREATE INDEX idx_<table>_<col> ON <table>(<col>);
   --rollback DROP TABLE <table>;
   ```

   Rules:
   - PostgreSQL syntax
   - **PKs are `UUID`, not `BIGSERIAL`** — matches every existing table in this schema
   - Money columns: `NUMERIC`
   - Timestamp columns: `TIMESTAMPTZ` (UTC — maps to `Instant`)
   - Tenant-scoped tables: `school_id UUID NOT NULL REFERENCES schools(id) ON DELETE CASCADE`
   - Any FK to `users(id)`/`schools(id)`: always `ON DELETE CASCADE`, or existing test teardown
     (`deleteAll()`) breaks on tables the test predates (`docs/COMMON_MISTAKES.md` #8)
   - Enum columns: `VARCHAR NOT NULL` (matches `@Enumerated(EnumType.STRING)`) — a `CHECK` constraint
     against the known value set is a good idea but not universally present in existing migrations;
     check a sibling table before deciding
   - Always index FK columns used in JOINs/WHERE
   - Always include a `--rollback` line
   - Forward-only in practice: once a changeset has shipped to `main`, write a **new** migration to
     change it — don't edit a shipped file (Liquibase tracks checksums)

3. **Register in the master changelog** — append to
   `src/main/resources/db/changelog/db.changelog-master.yaml`:
   ```yaml
     - include:
         file: NNN-<description>.sql
         relativeToChangelogFile: true
   ```
   Add it at the **end** of the existing includes — order matters, Liquibase runs sequentially.

4. **Also update domain docs**:
   - If adding a new table/entity, add it to `.claude/rules/java/schoolbridge-domain-model.md`
   - If adding a new enum value, note it there too (but the authoritative source is always the real
     `public enum` file — the doc is a convenience, not the source of truth)

5. **Verify** by running:
   ```bash
   mvnw.cmd -DskipTests compile
   ```
   Liquibase runs on Spring context startup, which happens as part of any `@SpringBootTest` — a
   quick way to confirm the changeset applies cleanly is running any integration test in the
   affected module.
