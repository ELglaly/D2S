---
name: add-tool
description: Add a new capability to the SchoolBridge AI assistant, backed by an existing service method.
argument-hint: [tool description, e.g. "let a teacher list their students' grade averages"]
allowed_tools: ["Bash", "Read", "Write", "Edit", "Grep", "Glob"]
---

# /add-tool $ARGUMENTS

Before scaffolding: confirm the capability described in `$ARGUMENTS`
already exists as a service method (and ideally a REST endpoint). If it
doesn't, this is a **new feature**, not a new tool — use `/new-module` or a
regular feature change first, get it green, then come back to this.

Use the **schoolbridge-assistant-tool** skill to implement it:

1. Pick the right `assistant/tools/<domain>` package.
2. Implement `ReadTool` or `AbstractActionTool` per the reference examples
   named in the skill.
3. Set `permissions()` to exactly match the backing endpoint's
   `@RequirePermission` — no looser.
4. Re-check row-ownership inside the tool at execution time; never trust
   the model's arguments for anything security-relevant; `schoolId` always
   comes from `TenantContext`.
5. Add this tool's row to the authorization-oracle test.
6. If the tool produces any user-facing string beyond the model-facing
   `description()`, use the **schoolbridge-i18n-message** skill for it.

Finish with `/verify`. Remember the whole module ships dark by default —
a new tool not showing up until flags are flipped is expected.
