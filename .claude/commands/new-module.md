---
name: new-module
description: Start the next module in SchoolBridge's gated build order. Checks the current gate is green before scaffolding.
argument-hint: [module-name]
allowed_tools: ["Bash", "Read", "Write", "Edit", "Grep", "Glob"]
---

# /new-module $ARGUMENTS

Before writing anything:

1. Confirm `$ARGUMENTS` is the correct next module per the Gated Build Order
   in `.claude/CLAUDE.md` (`tenant → identity → classes → announcements →
   integrations → attendance → homework → fees → messaging → reporting →
   audit → hardening`). If it's out of order, stop and say so — don't
   scaffold a module ahead of its gate.
2. Run `mvn -B -ntp verify` and confirm it's green *before* starting. If it
   isn't, the previous module isn't actually done — fix that first.

Then use the **schoolbridge-new-module** skill to scaffold
`$ARGUMENTS`, following its step order exactly: migration → entity → repo
→ DTO+mapper → service → controller → i18n → tests → gate.

If the entity is tenant-scoped, invoke the **schoolbridge-tenant-entity**
skill for the entity/repo steps specifically — don't hand-roll the
`findById` override from memory.

End by running `/verify` and updating `docs/api/openapi.{json,yaml}` if the
controller surface changed.
