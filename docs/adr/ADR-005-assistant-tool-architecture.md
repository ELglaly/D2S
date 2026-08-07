# ADR-005: AI assistant tools as thin adapters over existing services

**Status:** Accepted

## Context

The assistant lets users act ("mark this student absent", "publish this
homework") through natural language, in ar/en. This must never become a
second, divergent authorization or business-rule path next to the REST API.

## Decision

- A `Tool` is a thin adapter: `name()`, `description()`, `inputSchema()`
  (JSON Schema), `ToolKind` (`READ`/`ACTION`), `permissions()` (mirrors the
  backing endpoint's `@RequirePermission`), and a package-derived
  `ToolDomain` for catalog gating. It calls the **same service** the
  equivalent controller calls — never a repository directly, never
  duplicated business logic.
- `ToolRegistry` filters the catalog by the caller's role and the global
  actions kill-switch (`schoolbridge.assistant.actions.enabled`).
  `ToolDomain` further narrows the *offered* catalog by query intent, since
  the full role catalog is the dominant input-token cost.
- Coarse permission is declared on the tool and checked by `ToolAuthorizer`
  — the single source of truth, mirroring `@RequirePermission` on the
  matching endpoint. Fine-grained scope (row ownership — `teacherTeaches`,
  parent-link, etc.) is **re-checked inside the tool at execution time**,
  never trusted from the model's arguments (`schoolId` always comes from
  `TenantContext`, never from the LLM).
- Mutating tools go through `AbstractActionTool`: preview (no mutation) →
  confirmation token → execute (consumes token, re-guards, then calls the
  service). This is the same confirm-then-execute gate regardless of which
  `LlmGateway` engine is active (ADR-004).
- SSE responses are written synchronously to `HttpServletResponse.getWriter()`,
  not via `SseEmitter` — async re-dispatch through the security filter chain
  breaks the `TenantContext`/`SecurityContext` thread-locals the tools rely
  on. See `docs/COMMON_MISTAKES.md` for the concrete failure mode if this is
  ever "fixed" toward `SseEmitter`.
- A dedicated authorization-oracle test asserts exact role parity across the
  full tool catalog (no accidental `SUPER_ADMIN`-only leak, no accidental
  over-grant) — this is the security gate for the whole module.

## Consequences

- Every new tool requires a matching, already-existing service method — if
  the capability doesn't exist as a service call, it doesn't become a tool
  by shortcut.
- Adding a tool is mechanical: pick the domain package under
  `assistant/tools/<domain>`, implement `Tool`/`ReadTool`/`ActionTool`,
  register the permission, add the oracle-test row. See the
  `schoolbridge-assistant-tool` skill.
- The module ships dark (`schoolbridge.assistant.enabled=false`,
  `actions.enabled=false`) until explicitly turned on per environment.
