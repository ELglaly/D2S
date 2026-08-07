# PLAN — Permission-Based Tool Authorization for the AI Assistant

> Architecture review + migration plan for replacing role-based assistant-tool
> authorization (`Tool.roles()`) with permission-based authorization.
>
> Status: **proposal**. Supersedes the deferred "Option B" of
> `PLAN_ASSISTANT_TOOLS_REFACTOR.md §3` (FINDING #2). Ships dark behind the
> existing actions kill-switch. Do not commit without review.

---

## 0. TL;DR — the recommendation challenges the premise

The literal request is *"replace `Set<UserRole> roles()` with `Set<Permission>
permissions()` and filter the `ToolRegistry` by permission."*

**Do not do exactly that.** A naive 1:1 swap is a **functional regression and a
latent escalation trap**, because in this codebase *tool catalog visibility* and
*authorization* are two different axes that the request conflates:

1. **PARENT tools map to permissions PARENT does not hold.** `get_child_attendance`
   backs `ATTENDANCE_READ`; PARENT is granted only `GRADE_READ, HOMEWORK_READ,
   HOMEWORK_ACK, ANNOUNCEMENT_READ` (`015-authz.sql`). Filtering the catalog by
   `hasPermission(role, tool.permission)` deletes `list_my_children`,
   `get_child_attendance`, `get_child_absence_count`, and
   `respond_to_absence_alert` from every parent — `list_my_children` is the entry
   point of essentially every parent flow. `ToolPermissionGuardTest` already
   documents this carve-out ("PARENT is intentionally excluded").
2. **"Fixing" #1 by granting parents the coarse perms is an escalation** at the
   *controller* layer: a parent that holds `ATTENDANCE_READ` could call
   `GET /classes/{id}/attendance` for *any* class. Parent access is row-level
   (own child) and is enforced at execution by `PermissionsHelper.parentLinkedTo`,
   not by coarse grants.
3. **SUPER_ADMIN holds every permission.** A permission-derived catalog would
   auto-expose every tool to SUPER_ADMIN, violating the explicit
   `noToolTargetsSuperAdmin` oracle.

**Recommended design:** split the two axes and make **permissions the
authorization boundary enforced at *execution time*** via a dedicated
`ToolAuthorizer` (Option C), while keeping catalog visibility a derived,
token-cost concern. Tools declare `Set<Permission> permissions()` (coarse
capability, the DB single source of truth) **plus** an explicit
`AuthzModel`/`ownershipScoped` marker for row-level (parent/teacher-own) tools
that defer to `PermissionsHelper` at execution. This closes FINDING #2 (DB
re-maps now actually constrain the assistant) **without** breaking parents and
**without** escalating anyone.

---

## 1. Current authorization model (end-to-end)

### 1.1 Two parallel, independent authz models

| Path | Coarse gate | Row-level gate | Source of truth |
|------|-------------|----------------|-----------------|
| **REST controllers** | `@RequirePermission(Permission.X)` → `PermissionAspect` → `EffectivePermissionService.permissionsForRole(role)` (DB `role_permissions`, cached) | `@PreAuthorize("@perms.teacherTeaches(#id)")` etc. | **DB** `role_permissions` |
| **Assistant tools** | hardcoded `Tool.roles()` set, filtered in `ToolRegistry.toolsFor` | `PermissionsHelper.*` called **inside** each tool's `prepare`/`doExecute` | **compiled-in** `roles()` |

The two coarse gates are **not linked**. If an admin re-maps `role_permissions`
(the entire purpose of the RBAC layer), controllers follow; assistant tools do
not — their `roles()` are compiled in. Today only a *test*
(`ToolPermissionGuardTest`) pins the two together against seed data; nothing
enforces it at runtime.

### 1.2 The request flow

```
AssistantServiceImpl.answer / ConversationChatService.streamAnswer
  └─ registry.toolsFor(ctx)              // catalog: t.roles().contains(ctx.role())
  └─ selector.select(query, visible)     // token-cost domain narrowing (fail-open)
  └─ gateway → model emits tool_use
  └─ registry.find(call.name(), ctx)     // re-applies toolsFor (role visibility)
       ├─ ReadTool.execute(args, ctx)            → service call; in-tool @perms checks
       └─ ActionTool.preview→confirm→execute(ctx) → in-tool @perms re-guard, then service
```

Authorization for the tool path is therefore: **`roles()` visibility**
(`ToolRegistry`) + **`PermissionsHelper` row checks** (inside tools). The
DB-backed `Permission` layer is **not consulted on the tool path at all**.

### 1.3 Key components (read before editing)

- `assistant/tools/Tool.java` — interface; `Set<UserRole> roles()`.
- `assistant/tools/ToolRegistry.java` — `toolsFor`, `find`, `all`, `withRole`.
- `assistant/tools/ToolContext.java` — carries `role`, `principal`; **no
  permission set**.
- `assistant/tools/action/AbstractActionTool.java` — preview/confirm/execute
  skeleton; concrete tools re-guard in `doExecute`.
- `common/security/authz/Permission.java` — 31-value catalog (mirrors
  `permissions` table).
- `common/security/authz/EffectivePermissionService.java` —
  `permissionsForRole(role): Set<String>`, `@Cacheable` by role.
- `common/security/authz/PermissionAspect.java` — controller enforcement
  (reference pattern: ANY/ALL, 401 vs 403).
- `common/security/PermissionsHelper.java` (`@perms`) — row-level predicates
  (`teacherTeaches`, `parentLinkedTo`, …).
- `db/changelog/015-authz.sql` — seed grants (the authoritative role→perm map).
- Tests: `AssistantToolAuthorizationOracleTest` (tool→roles, exact),
  `ToolPermissionGuardTest` (tool→permission, staff-only).

### 1.4 Tool → permission → role-grant matrix (the crux)

`ToolPermissionGuardTest.TOOL_PERMISSIONS` is the existing authoritative 1:1
tool→permission map. Cross-referenced with `015-authz.sql` grants:

| Tool (PARENT) | Backing perm | PARENT holds it? |
|---|---|---|
| `get_child_homework` | `HOMEWORK_READ` | ✅ |
| `get_child_grades` | `GRADE_READ` | ✅ |
| `get_unacknowledged_announcements` | `ANNOUNCEMENT_READ` | ✅ |
| `acknowledge_homework` | `HOMEWORK_ACK` | ✅ |
| `acknowledge_announcement` | `ANNOUNCEMENT_READ` | ✅ |
| `list_my_children` | `STUDENT_READ` | ❌ |
| `get_child_attendance` | `ATTENDANCE_READ` | ❌ |
| `get_child_absence_count` | `ATTENDANCE_READ` | ❌ |
| `respond_to_absence_alert` | `ATTENDANCE_RECORD` | ❌ |

Four PARENT tools (❌) would vanish under naive permission filtering. **This is
the single most important fact in this document.** Staff (TEACHER/SCHOOL_ADMIN)
tools all have matching grants — the guard test already proves it — so the
migration is safe for staff and dangerous for parents.

---

## 2. Permission-based model design

### 2.1 Separate the two axes (the core architectural decision)

| Axis | Question | Failure mode if wrong | Where it must run |
|------|----------|-----------------------|-------------------|
| **Visibility / catalog** | Which tools to *advertise* to the model? | Wasted tokens; model can't find a tool (mitigated by `ToolSelector` fallback) | catalog build (`toolsFor`) |
| **Authorization** | May this user *actually run* this tool? | **Privilege escalation** | execution (`find` → preview/execute) |

The request collapses both into the catalog filter. That is why it breaks: the
catalog filter is the wrong place to encode the security boundary, and a coarse
permission is the wrong key for row-scoped parent tools. **Authorization is the
boundary; make it explicit and enforce it at execution.**

### 2.2 What a tool should expose

**Chosen: `Set<Permission> permissions()` (ANY-of semantics) + an `AuthzModel`.**

```java
public interface Tool {
  String name();
  String description();
  JsonNode inputSchema();
  ToolKind kind();
  ToolDomain domain();          // unchanged (token gating)

  /** Coarse capability this tool exercises; mirrors the backing endpoint's
   *  @RequirePermission. The DB role→permission grants are the source of truth. */
  Set<Permission> permissions();

  /** How {@link Permission} authorizes this tool. */
  AuthzModel authzModel();      // default ROLE_GRANT
}
```

```java
/** Whether a tool is authorized by a coarse role→permission grant or by a
 *  row-level ownership predicate evaluated inside the tool at execution time. */
public enum AuthzModel {
  /** User's role must hold (ANY of) {@link Tool#permissions()} in role_permissions. */
  ROLE_GRANT,
  /** Authorized by an ownership predicate (PermissionsHelper.*) inside the tool;
   *  the coarse grant is intentionally NOT required (e.g. a parent reading their
   *  own child's attendance). {@link Tool#permissions()} documents the analogous
   *  capability but is not gated by the registry/authorizer. */
  OWNERSHIP_SCOPED
}
```

#### Why `Set<Permission> permissions()` and not `boolean supports(Permission)`

| | `Set<Permission> permissions()` ✅ | `boolean supports(Permission)` ❌ |
|---|---|---|
| Introspectable | Yes — oracle test reads it, docs/audit enumerate it | No — caller must guess which permission to probe |
| Authz logic location | Centralized in `ToolAuthorizer` | Smeared into each tool (per-tool branching) |
| Testability | Trivial (data) | Must enumerate all perms to discover the set |
| Coupling | Tool depends only on the `Permission` enum (it already does, via `@RequirePermission` parity) | Inverts control; tool owns the matching rule |
| SOLID | SRP/OCP: tool *declares*, authorizer *decides* | Tool both declares and decides |

`supports(...)` is the classic "ask, don't tell" inversion that scatters policy.
Declarative data wins. **ANY-of** (not ALL-of) matches `PermissionAspect`'s
default and is future-proof for a tool spanning two endpoints; today every tool
is 1:1 so the set is a singleton.

> Alternative considered — a single `Permission permission()`: simpler, matches
> today's 1:1 reality, but a Set costs nothing and avoids a breaking signature
> change the first time a tool needs two. Use the Set.

#### Avoid 55 copy-paste overrides — base defaults

Add the binding to the existing base types so most tools change one line, not
three. `AbstractActionTool` already centralizes action plumbing; mirror that:

- Default `authzModel()` → `ROLE_GRANT` in the `Tool` interface.
- For ownership-scoped parent tools, override `authzModel()` →
  `OWNERSHIP_SCOPED`.
- `permissions()` stays per-tool (it is genuinely tool-specific) but is a
  one-liner sourced directly from the existing `TOOL_PERMISSIONS` guard map.

> Optional sugar (not required): an annotation `@ToolPermission(Permission.X)` +
> `@ToolAuthz(OWNERSHIP_SCOPED)` read reflectively, so tools and controllers
> share one authz vocabulary. Defer; the interface method is enough.

---

## 3. Tool interface migration

**Current:** `Set<UserRole> roles()`. **Target:** `Set<Permission> permissions()`
+ `AuthzModel authzModel()`; `roles()` **removed**.

- **Interface change:** add `permissions()` + `authzModel()` (default); later
  delete `roles()`.
- **Breaking change:** removing `roles()` breaks every `Tool` impl (55) and two
  tests. All are in-repo — mechanical, compiler-guided. No external implementers.
- **`ctx.role()` stays.** `ToolContext.role()` / `isParent()` / `isAdmin()` are
  used by `PermissionsHelper` and message selection, not just gating — keep them.
  Only the *gating* use of role moves to permissions.
- **Backward-compat option (recommended during rollout):** keep `roles()` as a
  `@Deprecated default` returning `Set.of()` for one phase so the codebase keeps
  compiling while tools are migrated tool-by-tool; delete it in the final phase.
  This makes the change reviewable in small commits instead of one 60-file diff.
- **Migration risk:** a tool that forgets to override `permissions()` would
  default to empty → `ROLE_GRANT` with empty set → **denied** (fail-closed, good)
  → tool silently disappears. The migrated oracle/guard test (below) catches this
  at CI because every registered tool must declare a non-empty permission set.

---

## 4. `ToolRegistry` refactor + the `ToolAuthorizer`

### 4.1 Evaluate the three options from the brief

- **Option A — inline `permissionService.hasAnyPermission(...)` in the registry
  stream.** Rejected: keeps authz in the catalog layer (wrong axis), still
  breaks parents (no ownership carve-out), duplicates the check at the three
  execution call sites (`find` in two services), couples the registry to the
  permission service.
- **Option B — generic `authorizationService.canAccessTool(ctx, t)`.** Acceptable
  but vague; a "do-everything" authz service tends to accrete unrelated rules.
- **Option C — dedicated `ToolAuthorizer.canUseTool(ctx, t)`.** **Recommended.**
  SRP: registry stays a dumb holder + deterministic sorter; the authorizer owns
  the one place where coarse-permission ∨ ownership-scoped is decided, and it is
  reused at *both* catalog build and execution (so the boundary cannot be
  bypassed by the `find` path). Single seam to unit-test.

### 4.2 `ToolAuthorizer` (the security boundary)

```java
@Component
public class ToolAuthorizer {

  private final EffectivePermissionService permissions;

  public ToolAuthorizer(EffectivePermissionService permissions) {
    this.permissions = permissions;
  }

  /** Coarse capability decision. SUPER_ADMIN is explicitly NOT auto-granted tools
   *  (tools are deferred for it); ownership-scoped tools defer their real check to
   *  the tool body (PermissionsHelper) and pass the coarse gate here. */
  public boolean canUseTool(Tool tool, ToolContext ctx) {
    return canUseTool(tool, ctx, permissions.permissionsForRole(ctx.role()));
  }

  /** Overload that takes the already-resolved grant set, so a catalog build of N
   *  tools does ONE permission lookup, not N. */
  public boolean canUseTool(Tool tool, ToolContext ctx, Set<String> granted) {
    if (ctx.role() == UserRole.SUPER_ADMIN) {
      return false; // preserve noToolTargetsSuperAdmin; "holds all perms" must not auto-expose
    }
    if (tool.authzModel() == AuthzModel.OWNERSHIP_SCOPED) {
      return true;  // real authorization is the tool's row-level @perms check at execution
    }
    return tool.permissions().stream().map(Enum::name).anyMatch(granted::contains);
  }
}
```

> `SUPER_ADMIN` handling is policy, not infrastructure — if/when tools are
> enabled for SUPER_ADMIN, flip this to fall through. Today it must short-circuit.

### 4.3 `ToolRegistry` after

```java
@Component
public class ToolRegistry {

  private final List<Tool> tools;
  private final ToolAuthorizer authorizer;
  private final EffectivePermissionService permissions;
  private final boolean actionsEnabled;

  // ...ctor...

  public List<Tool> toolsFor(ToolContext ctx) {
    Set<String> granted = permissions.permissionsForRole(ctx.role()); // ONE lookup/request
    return tools.stream()
        .filter(t -> authorizer.canUseTool(t, ctx, granted))
        .filter(t -> actionsEnabled || t.kind() == ToolKind.READ)
        .sorted(Comparator.comparing(Tool::name))   // determinism preserved
        .toList();
  }

  public Optional<Tool> find(String name, ToolContext ctx) {
    return toolsFor(ctx).stream().filter(t -> t.name().equals(name)).findFirst();
  }
}
```

- `find()` already re-applies `toolsFor`, so the **execution path inherits the
  same boundary** — no separate enforcement needed in `AssistantServiceImpl` /
  `ConversationChatService`. (Belt-and-suspenders option: also assert
  `authorizer.canUseTool` immediately before `preview`/`execute`; cheap, defends
  against a future caller that bypasses `find`.)
- Row-level enforcement is **unchanged** — parent/teacher-own checks stay inside
  the tools via `PermissionsHelper`. The authorizer is *only* the coarse layer.
- `withRole(UserRole)` (oracle helper) → replace with `withPermission(Permission)`
  or delete once the oracle test is migrated.

### 4.4 Determinism & provider caching

Filtering is still pure + stable given the grant set, and the post-filter
`sorted` is unchanged → the serialized catalog stays byte-identical across
requests **for a fixed grant set**. New nuance: the catalog now depends on the
*mutable* DB grants. An admin re-mapping `role_permissions` mid-flight changes
the advertised catalog → provider prefix-cache invalidates for that role until
re-warmed. That is correct behavior (the point of the migration) and rare; note
it, don't fight it. `EffectivePermissionService` already evicts its cache on
re-map, so the change propagates without re-login.

---

## 5. Security review

- **Least privilege — improved.** Coarse capability is now DB-driven for the tool
  path; revoking `GRADE_CREATE` from TEACHER in `role_permissions` immediately
  removes `create_grade`/`update_grade` from the assistant. Today it does not.
- **Escalation during migration — the headline risk.** Do **not** "solve" the
  parent breakage by granting parents `ATTENDANCE_READ`/`STUDENT_READ`/
  `ATTENDANCE_RECORD` — that escalates them at the *controller* layer. Use
  `OWNERSHIP_SCOPED` (coarse gate skipped, row check retained) instead. This
  keeps parent authorization exactly where it is today.
- **SUPER_ADMIN auto-exposure — must be explicitly blocked** (`canUseTool`
  short-circuit) or the permission model silently exposes all 55 tools to it,
  breaking `noToolTargetsSuperAdmin`.
- **Missing-check closure.** `find()` re-applies the filter, so a model that
  hallucinates a tool name the user isn't authorized for gets
  `Optional.empty()` → no execution. Unchanged, still holds.
- **Fail-closed default.** Empty `permissions()` + `ROLE_GRANT` → denied. A new
  tool that forgets its binding disappears rather than over-exposing. CI guard
  (below) turns the silent disappearance into a red build.
- **Smell to document (not block):** `acknowledge_announcement` /
  `acknowledge_homework` are *actions* (writes) bound to `ANNOUNCEMENT_READ` /
  `HOMEWORK_ACK`. `HOMEWORK_ACK` is correct; `ANNOUNCEMENT_READ` for an ack-write
  is loose but matches the controller and is parent-ownership-gated — leave as-is,
  note for a later `ANNOUNCEMENT_ACK` perm.
- **Multi-tenant.** `permissions`/`role_permissions` are platform-global
  (`015` comment) — no per-school grants yet. The migration does not change this;
  tenant-scoped grants are future work (§7). No regression.
- **Audit.** Optionally emit a tool-authz-denied audit event (reuse the
  outbox/audit path) when `find` returns empty due to authz, for monitoring
  prompt-injection probing. Out of scope for the core change.

---

## 6. Performance review

- **Permission lookup.** `EffectivePermissionService.permissionsForRole` is
  `@Cacheable` by role → one `Set<String>` per role, warm after first hit, no DB
  on the hot path. Resolve it **once per request** and pass to `canUseTool`
  (overload) — do **not** call it per-tool (still a cache hit, but needless).
- **Filtering.** N ≈ 55 tools × `Set.contains` (singleton ANY) = negligible
  vs. the LLM round-trip; dwarfed by network. No change in complexity class.
- **Catalog generation / token cost.** Unchanged — `ToolSelector` domain gating
  still runs after authz filtering exactly as today.
- **Determinism / caching.** Preserved (§4.4); only new dependency is the grant
  set, which is stable between re-maps.
- **Micro-optimization (optional):** cache `permissions().stream().map(name)` as a
  precomputed `Set<String>` per tool (tools are singletons) to avoid per-request
  re-mapping. Trivial; skip unless profiling says so.

---

## 7. Architecture recommendations (beyond the ask)

1. **Parent/child-scoped permissions (the principled fix for the carve-out).**
   `OWNERSHIP_SCOPED` is pragmatic but is an exemption, not a model. Introduce
   `CHILD_ATTENDANCE_READ`, `CHILD_GRADE_READ`, `CHILD_HOMEWORK_READ`,
   `CHILD_ABSENCE_RESPOND` granted to PARENT and applied on the child-scoped
   controller endpoints. Then parent tools become plain `ROLE_GRANT` and the
   carve-out disappears. Bigger change (enum + seed + endpoints); do after the
   core migration lands.
2. **One authz vocabulary.** `@ToolPermission(...)` annotation read reflectively
   (mirroring `@RequirePermission`) so controllers and tools express
   authorization identically; a single doc-gen could list both.
3. **Push `@RequirePermission` to the service layer** (old plan's Option A): tools
   call services directly, so service-level enforcement would make the tool path
   inherit DB enforcement *for free* and remove the possibility of drift
   entirely. Largest blast radius (every service + controller test); the
   `ToolAuthorizer` is the pragmatic 80/20 now, service-layer enforcement is the
   long-term north star.
4. **Tenant-level grants / feature flags.** Make `role_permissions` optionally
   tenant-scoped (school override row) so a school can disable e.g.
   `ANNOUNCEMENT_SEND` for teachers. `EffectivePermissionService` key becomes
   `(tenant, role)`. Natural home for per-school assistant feature flags.
5. **Bind `ToolDomain` ↔ permission groups.** Domains already exist for token
   gating; a `domain → default permission set` map could seed/validate tool
   bindings and power admin UI ("which capabilities does the assistant expose for
   GRADES?").

---

## 8. Per-class change table

| Class | Current | Required change | Risk |
|---|---|---|---|
| `Tool` | `Set<UserRole> roles()` | add `Set<Permission> permissions()` + `AuthzModel authzModel()` (default `ROLE_GRANT`); deprecate then remove `roles()` | breaking (in-repo only) |
| `AuthzModel` (new) | — | `ROLE_GRANT` / `OWNERSHIP_SCOPED` enum | none |
| `ToolAuthorizer` (new) | — | `canUseTool(tool, ctx[, granted])`; SUPER_ADMIN block; ownership carve-out | central — unit-test hard |
| `ToolRegistry` | role filter | authorizer-based filter; one grant lookup/request; `withRole`→`withPermission`/delete | medium |
| `ToolContext` | `role`, `principal` | **no change** (`role()` still needed downstream) | none |
| 5 PARENT tools (`list_my_children`, `get_child_attendance`, `get_child_absence_count`, `respond_to_absence_alert`, + the 3 already-held) | `roles()=Set.of(PARENT)` | `permissions()` from guard map; ❌-rows → `authzModel()=OWNERSHIP_SCOPED` | **high — verify parents keep all tools** |
| ~50 staff tools | `roles()=Set.of(T[,A])` / `Set.of(A)` | `permissions()` from guard map; `ROLE_GRANT` (default) | low (guard test proves grants exist) |
| `AssistantServiceImpl` / `ConversationChatService` | call `toolsFor`/`find` | no change needed (boundary inherited); optional pre-exec assert | low |
| `AssistantToolAuthorizationOracleTest` | tool→roles exact | retarget to tool→`permissions()`+`authzModel()`; keep SUPER_ADMIN exclusion | medium (test rewrite) |
| `ToolPermissionGuardTest` | side `TOOL_PERMISSIONS` map | becomes redundant — its map MOVES into the tools; test now reads `tool.permissions()` and asserts each non-empty + (staff) grant-backed | medium |

---

## 9. Recommended implementation order

> One module, gated build cadence; `mvn -B -ntp verify` green at each phase;
> ships dark behind `schoolbridge.assistant.actions.enabled` (already off).

- **P0 — scaffolding (no behavior change).** Add `AuthzModel`; add
  `permissions()` (default `Set.of()`) + `authzModel()` (default `ROLE_GRANT`) as
  **defaults** on `Tool`; keep `roles()`. Build still green, nothing wired.
- **P1 — declare bindings.** Override `permissions()` on all 55 tools from the
  existing `TOOL_PERMISSIONS` map; set `OWNERSHIP_SCOPED` on the four ❌ parent
  tools (and any teacher-own read you want row-gated). Still no filtering change.
- **P2 — `ToolAuthorizer` + tests.** Add the authorizer; unit-test ROLE_GRANT
  (staff allow/deny), OWNERSHIP_SCOPED (always coarse-pass), SUPER_ADMIN block.
- **P3 — switch the boundary.** `ToolRegistry.toolsFor` uses the authorizer
  (one grant lookup). Run the **full assistant suite + parent flows** — the
  acceptance gate is *parents still see all 9 parent tools*.
- **P4 — migrate the guard/oracle tests.** Retarget oracle to
  `permissions()`+`authzModel()`; fold `ToolPermissionGuardTest`'s map into a
  "every tool declares a non-empty backing permission and staff roles hold it"
  assertion reading `tool.permissions()`.
- **P5 — delete `roles()`.** Remove from `Tool` + all impls + `withRole`. Final
  green build. (Compiler drives the cleanup.)
- **P6 (optional, separate PR) — principled parent perms** (§7.1) to retire
  `OWNERSHIP_SCOPED` for reads.

Phases P0–P2 are no-ops behaviorally and can land first to de-risk review.

---

## 10. Potential pitfalls (checklist)

- [ ] **Parents lose tools** — the four `OWNERSHIP_SCOPED` carve-outs are
  mandatory; assert parent catalog size in a test.
- [ ] **SUPER_ADMIN auto-exposure** — explicit short-circuit in `canUseTool`.
- [ ] **Per-tool permission lookups** — resolve the grant set once per request.
- [ ] **Empty `permissions()` default** — fail-closed is correct, but add a CI
  guard so a new tool can't silently vanish.
- [ ] **`ctx.role()` over-removal** — it is used beyond gating
  (`PermissionsHelper`, message selection); keep it.
- [ ] **Provider prefix-cache churn** on `role_permissions` re-map — expected;
  document, don't fight.
- [ ] **Action vs read permission mismatch** (`acknowledge_*` → READ perm) —
  acceptable, documented, future `*_ACK` perm.
- [ ] **`withRole` / oracle helper** still referencing `roles()` — migrate in P4.
- [ ] **i18n** — no user-facing strings change (denied tools simply aren't
  advertised); no new ar/en keys required.

---

## 11. Decision

Adopt the **two-axis design**: `Set<Permission> permissions()` + `AuthzModel` on
`Tool`, a dedicated **`ToolAuthorizer`** as the coarse boundary enforced via
`ToolRegistry` (and thus `find`/execution), DB `role_permissions` as the single
source of truth, and the explicit `OWNERSHIP_SCOPED` carve-out so parent
row-level authorization is preserved unchanged. This delivers what the request
*wants* (permissions, not compiled-in roles; centralized; extensible) while
refusing the literal swap that would regress parents and risk escalation.
```
