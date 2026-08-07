---
name: gate-check
description: Definition-of-Done readiness check for the module currently being closed out — tenant isolation, i18n parity, and the Maven gate together.
allowed_tools: ["Bash", "Read", "Grep", "Glob"]
---

# /gate-check

Checks whether the **current** module (per the Gated Build Order in
`.claude/CLAUDE.md`) is actually ready to close, per
`docs/CHECKLISTS.md` → Definition of Done. Run this before starting the
next module, not after — a module that isn't done blocks everything behind
it.

## Steps

1. Run the **tenant-isolation-auditor** agent, scoped to any repository
   files touched or added for this module. Any missing `findById` override
   is a blocker, not a nice-to-have.
2. Run the **i18n-parity-auditor** agent. Any key present in one locale
   file and missing from the other is a blocker.
3. Run `/verify` (Spotless + SpotBugs + full test suite).
4. Check `docs/api/openapi.{json,yaml}` is current if any controller in
   this module changed — diff the endpoints against the spec by eye if
   there's no automated regeneration step wired up yet.
5. Check for new `TODO`s without a linked follow-up (grep the module's
   diff for `TODO`).

## Output

One consolidated readiness report:

```
GATE CHECK: <module> — [READY / NOT READY]

Tenant isolation:  [clean / N findings]
i18n parity:       [clean / N findings]
Maven verify:      [PASS / FAIL]
OpenAPI docs:      [current / stale]
Loose TODOs:       [none / N found]

Blocking items (if any):
- ...
```

Don't mark a module READY if any item above is unresolved — the whole point
of the gate order is that the next module starts from a genuinely clean
base.
