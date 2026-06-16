# AI Assistant Module v2 — Implementation Plan (grounded)

> Companion to `docs/PLAN_AI_ASSISTANT_V2.md` (the design). This document is the **execution plan**,
> grounded in the actual codebase signatures discovered during planning.
> **Status: Phases 1–4 implemented and `mvn verify`-green at each phase boundary.** See
> "Implementation status" at the bottom for what shipped and the few remaining follow-ups.

## Context

Build the action-capable natural-language assistant (`POST /api/v1/assistant/ask`) for parents,
teachers, and school admins, in Arabic + English. The LLM (Anthropic) selects typed **tools** (thin
Java adapters over existing services); all authorization, the confirm-then-execute gate, idempotency,
and audit are deterministic server code. **The LLM never touches the DB, never sees a UUID, and never
decides authorization or confirmation.** Ships dark (`enabled=false`, `actions.enabled=false`).

The `com.schoolbridge.api.assistant` package does not exist yet. Every backing service, DTO, principal
type, and infra component the design assumes already exists, so this is purely additive: a new package
+ one pom dependency + one config block. The assistant depends on none of `fees/messaging/reporting`,
so building it now skips no real dependency.

## Confirmed decisions

1. **Scope:** Full v2 — Phases 1–4. Super Admin (Phase 5) deferred. `mvn verify` green at each phase gate.
2. **Anthropic SDK:** real `com.anthropic:anthropic-java` pinned to **2.35.0**; `AnthropicLlmGateway`
   behind `enabled=true`; everything else against the `LlmGateway` interface so tests never touch the
   SDK. Smoke-test the Windows-JDK HTTP factory (mirror the `SimpleClientHttpRequestFactory` gotcha).
3. **Destructive actions** (`delete_student/class/grade/subject`, `recall_announcement`): exposed to
   School Admin, require a **typed** `yes`/`نعم` (not a tap), `destructive-require-typed-confirm=true`.
4. **Action ledger:** `audit_logs` only — no `assistant_action` migration for v2.
5. One-confirm-per-mutation (no batch auto-execute). Teacher→admin wall = refuse with a helpful
   localized message (no auto-draft).

## Reuse map (exact signatures)

**Security / infra:**
- `common/security/PermissionsHelper` `@Component("perms")` — predicates read `SecurityContextHolder`
  directly: `teacherTeaches(UUID)`, `teacherTeachesSubject(UUID,UUID)`, `parentLinkedTo(UUID)`,
  `canSendAnnouncementToScope(AnnouncementScope,String)`, `parentReceivedAnnouncement(UUID)`,
  `isAnnouncementSender(UUID)`, `isHomeworkAuthor(UUID)`. No admin predicate — admin =
  `staff.role()==SCHOOL_ADMIN`.
- Principals: `StaffPrincipal(UUID userId, UUID schoolId, UserRole role)`,
  `ParentPrincipal(UUID userId, UUID schoolId)` — resolve from `Authentication.getPrincipal()` via
  `requireStaff/requireParent` throwing `TenantSecurityException` (mirror `AttendanceController`).
- `UserRole` = {SUPER_ADMIN, SCHOOL_ADMIN, TEACHER, PARENT}; SUPER_ADMIN is a separate `PlatformAdmin`.
- `common/tenancy/TenantContext.require()` → `UUID schoolId`.
- `common/idempotency/IdempotencyService`: `tryAcquire(String)`, `release`, `find`, `store(key,int,byte[])`.
- `common/audit/AuditService.record(schoolId, actorUserId, action, entityType, entityId, Map metadata)` —
  build metadata with **`HashMap`**, never `Map.of`.
- `common/i18n/MessageResolver.get(key, args...)` — locale from `LocaleContextHolder`; `messages_ar/en.properties`.
- `common/web/ApiResponseBodyAdvice.supports()` wraps only Jackson + `com.schoolbridge.api` → SSE not wrapped.
- `identity/auth/LoginRateLimiter` = rate-limit pattern; Resilience4j 2.2.0 present (mirror `whatsapp` instance).
- `SecurityConfig` catch-all `.anyRequest().authenticated()` → `/assistant/**` is authenticated automatically.
- Config template: `integrations/whatsapp/WhatsAppProperties` (`@ConfigurationProperties`).

**Backing services (signatures):** see `docs/PLAN_AI_ASSISTANT_V2.md` §19 and §8; corrections applied:
grade/homework `create(schoolId, actorId, req)`; many `list*` return `Page<T>` (pass `PageRequest.of(0,N)`);
class-subject ops are `assign/delete`; admin checks from `staff.role()`.

## Phases

### Phase 1 — Tool foundation + read tools
`tools/{Tool,ReadTool,ActionTool(decl),ToolKind,ToolContext,ToolResult,ToolRegistry}`,
`tools/support/*` (name→id resolvers: 0=clarify, 1=proceed, >1=clarify candidates),
`tools/read/*` (~14: parent/teacher/admin R rows). Tests: happy/denied/ambiguous + registry filtering.

### Phase 2 — LLM gateway + read orchestration
`pom.xml` (+anthropic 2.35.0), `application.yml` (`schoolbridge.assistant` block + resilience4j instance),
`llm/{AssistantProperties,LlmGateway,AnthropicLlmGateway(@ConditionalOnProperty enabled=true),
AnthropicClientConfig,SystemPrompt}`, `AssistantService(Impl)` read loop (max 4 iters),
`cache/AssistantCache` (read asks only). Tests: stubbed gateway (single/multi/max-iter/denied).

### Phase 3 — Action layer
`tools/ActionTool` (`preview` no-mutate + store single-use PendingAction; `execute` re-guard + service +
idempotency), `confirm/{PendingAction,PendingActionStore(Redis 5-min single-use),ConfirmationTokenService
(HMAC, user-bound)}`, `tools/action/*` (~30 A rows). §11.4 ownership re-checks on `update_grade/delete_grade`.
Orchestrator gate: ACTION→preview→halt→`confirmRequired` SSE. Tests: preview-no-mutate, denied, ambiguous,
confirm→execute, replay/expiry/wrong-user, destructive typed-confirm, idempotent double-confirm, max-bulk refusal.

### Phase 4 — Endpoint, cache, audit, hardening
`AssistantController` (SSE `/ask`; `/actions/{token}/confirm` + `/cancel` wrapped JSON; yes/no intent
classifier), `audit/AssistantAuditRecorder` (HashMap metadata), rate limiting (429), circuit breaker +
timeout, Micrometer metrics, i18n ar+en strings, **authorization oracle test** (§18 Appendix),
integration tests (SSE unwrapped, cache-hit, confirm→execute→audit, cancel, 429, two-school isolation,
role tool availability). Gate: `mvn verify` green, 80%+ coverage.

## Out of scope (v2)
Phase 5 Super Admin; `assistant_action` table; batch auto-execute; live-API E2E (env-gated, off CI).

## Verification
Per phase: `mvn spotless:apply` then `mvn -B -ntp verify` green. Ships dark via `enabled=false` /
`actions.enabled=false`. §18 oracle test is the security-parity gate.

---

## Implementation status

All four phases are implemented under `com.schoolbridge.api.assistant` and each phase boundary passed
a full `mvn -B -ntp verify` (compile + all tests + Spotless + SpotBugs).

**Shipped:**
- **Foundation + 19 read tools** (parent/teacher/admin) over existing services, each re-running the
  matching `@perms`/role guard; name→id resolution keeps UUIDs out of the model.
- **LLM gateway** decoupled behind `LlmGateway`; real `AnthropicLlmGateway` (anthropic-java 2.35.0,
  OkHttp — no Windows-JDK abort) gated on `enabled=true`; `DisabledLlmGateway` fallback keeps the app
  bootable when off. Read orchestration loop with cache + max-iter cap.
- **Action layer**: `AbstractActionTool` (bulk cap → token → Redis store → single-use GETDEL consume →
  user/expiry re-check → re-guard → service) + **31 action tools** (parent 3, teacher 9, admin 19).
  §11.4 ownership re-checks on grade/homework/announcement actions.
- **Endpoints**: `AssistantController` — SSE `/ask` (frames sent as raw JSON strings so the
  Jackson-only `ApiResponseBodyAdvice` never wraps them); wrapped `/actions/{token}/confirm` + `/cancel`;
  deterministic ar/en yes-no `ConfirmIntent`; destructive typed-confirm gate.
- **Hardening**: per-user Redis rate limit → 429; Resilience4j circuit breaker on the gateway;
  Micrometer counters; `AssistantAuditRecorder` (ask / preview / execute / cancel, HashMap metadata);
  full ar+en i18n; **§18 authorization-oracle test** (51 tools, exact role parity, no SUPER_ADMIN).
- **Tests**: ~45 assistant tests — name matching, registry filtering, tool resolution, read-loop,
  action machinery (preview-no-mutate / replay / expiry / wrong-user / bulk-cap), confirm service,
  cache, prompt, intent, the oracle, an enabled=true context-wiring test, **and a full HTTP
  integration test** (`AssistantIntegrationTest`, enabled + scripted gateway): read answer streamed
  **un-wrapped** over SSE; `confirmRequired → /confirm → execute` writes the attendance row + the
  `assistant.action.preview`/`.execute` audit rows; single-use token replay → INVALID; and a teacher
  **cannot reach another school's class** (two-school tenant isolation).

**Config:** `schoolbridge.assistant.*` block in `application.yml` (dark defaults) + resilience4j
`assistant` circuit-breaker/timelimiter instances; `anthropic-java` pinned in `pom.xml`.

**Remaining follow-ups (none security-critical — role parity, the confirm gate, single-use/expiry/
user-binding, and two-school isolation are all test-covered):**
- True token-by-token streaming. The `/ask` answer is computed synchronously (keeps tenant + security
  thread-locals valid) and written as SSE frames; it currently emits the completed answer as one
  `delta`. Real streaming needs a streaming `LlmGateway` + careful thread-local propagation.
- A parent-authenticated `/ask` test (parents use opaque OTP tokens, not staff JWTs); the role-parity
  oracle + the action machinery already cover parent tools.
- Optional `assistant_action` durable ledger (deferred per decision — `audit_logs` is the record).
- Phase 5 Super Admin (`add_user`, school management) — deferred.
