# ADR-001: Liquibase forward-only migrations + Spotless google-java-format

**Status:** Accepted

## Context

The project needs a deterministic, team-wide-consistent way to evolve the
database schema and to format Java code, without relying on developer
discipline alone.

## Decision

- **Migrations:** Liquibase only, YAML changelogs under
  `src/main/resources/db/changelog/`, registered in
  `db.changelog-master.yaml`. Migrations are **forward-only** — an already-
  applied changeset is never edited; a mistake is corrected by a new
  changeset, not a rewrite of history.
- **Formatting:** Spotless with google-java-format, 2-space indent, enforced
  as a hard gate in `mvn verify` (not just a suggestion). A PostToolUse hook
  runs `mvn -q spotless:apply` after every file write/edit so formatting
  drift never accumulates.

## Consequences

- Every environment (dev, CI, prod) reaches the same schema by replaying the
  same ordered changeset list — no manual DDL, no drift.
- `removeUnusedImports` runs as part of Spotless and will strip an import
  added before its first usage if they land in separate edits — see
  `docs/COMMON_MISTAKES.md` #9.
- SpotBugs (effort=Max, threshold=Medium) is a separate, also-hard gate;
  green tests do not imply a green `verify`.
