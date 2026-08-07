---
name: schoolbridge-assistant-tool
description: Add a new tool to the SchoolBridge AI assistant (com.schoolbridge.api.assistant.tools). Use when the assistant needs to expose a new read or action capability that already exists as a REST endpoint/service method.
---

# SchoolBridge: add an AI assistant tool

Read `docs/adr/ADR-005-assistant-tool-architecture.md` first. A tool is a
**thin adapter over an existing service** — if the capability doesn't exist
as a service method yet, build and land that first (via
`schoolbridge-new-module` or a regular feature change), get it green, then
add the tool as a follow-up.

## Steps

1. **Pick the domain package**: `assistant/tools/<domain>` — one of
   `announcements`, `attendance`, `classes`, `grades`, `homework`, `parents`,
   `read`, `staff`, `student`, `subjects`, `support`, `action`, matching
   `ToolDomain.fromPackage`. If none fits, the tool falls into `GENERAL` —
   confirm that's intended before adding a new domain bucket.

2. **Implement the interface**:
   - Read-only → `ReadTool` (see `GetChildAttendanceTool` as the reference
     shape: constructor-injects `ToolSupport`, `PermissionsHelper`, and the
     backing service; `name()` returns a stable snake_case id; `description()`
     is one line aimed at the model; `inputSchema()` built with
     `Schema.builder()`).
   - Mutating → extend `AbstractActionTool` (preview: no mutation, returns a
     confirmation token; execute: consumes the token, re-runs the
     permission/ownership guard, then calls the service).

3. **Permissions**: `permissions()` returns the **same** `Permission` set as
   the backing endpoint's `@RequirePermission` — this is the single source
   of truth `ToolAuthorizer` checks against. Never invent a looser
   permission for the tool than the REST endpoint requires.

4. **Row-ownership / scope**: re-check inside the tool at execution time
   (`@perms`-equivalent logic via the injected service/`PermissionsHelper`),
   never trust the model's arguments for anything security-relevant.
   `schoolId` always comes from `TenantContext`, never from an LLM-supplied
   argument.

5. **Register**: tools are typically `@Component`-scanned into
   `ToolRegistry` automatically — confirm no manual registration list needs
   updating.

6. **Oracle test**: add this tool's row to the authorization-oracle test
   (asserts exact role parity across the full catalog — the security gate
   for this module, §18 in `docs/PLAN_AI_ASSISTANT_V2.md`). A tool with the
   wrong role exposure is a security bug, not a style nit.

7. **i18n**: any user-facing string the tool produces (not just the model
   description) follows the same ar/en parity rule —
   `schoolbridge-i18n-message` skill.

## Gotchas specific to this module

- The `/ask` endpoint writes SSE frames synchronously to
  `HttpServletResponse.getWriter()`, not via `SseEmitter` — don't try to
  make a tool "async" in a way that moves execution off the request thread;
  it breaks the `TenantContext`/`SecurityContext` thread-locals the tool
  relies on.
- Everything in this module ships dark by default
  (`schoolbridge.assistant.enabled=false`, `actions.enabled=false`) — a new
  tool being "invisible" until flags are flipped is expected, not a bug.
- Outbox/audit metadata for tool calls is built with `HashMap`, never
  `Map.of(...)` (`docs/COMMON_MISTAKES.md` #3).
