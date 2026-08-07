# ADR-007: Scope correction and assistant freeze

**Status:** Accepted

## Context

A platform review (2026-08-07, [`docs/PLATFORM_REVIEW.md`](../PLATFORM_REVIEW.md))
was commissioned against a feature list that described SchoolBridge as already
shipping messaging, bus tracking with shareable parent-subscription links, a
school calendar, file attachments, admin and analytics dashboards, WebSocket
real-time communication, and notification preferences.

Verification against the codebase found that none of those exist. There is no
messaging module, table, or endpoint — `assistant/conversation/*` is AI-chat
history, not teacher↔parent messaging. There is no transport code of any kind.
No `spring-boot-starter-websocket`. `attachment_key` is an opaque `VARCHAR(512)`
string with no upload, storage, scanning, or signing behind it, and MinIO sits in
`docker-compose.yml` with no client in `pom.xml`. No aggregate or report endpoints
exist.

Meanwhile `assistant/` is 149 files and 10,833 LOC — about 40% of all main source —
and 31 of the 84 test classes. It carries two parallel LLM engines (hand-written
native gateways and Spring AI), three providers, a 60+ tool registry with a
confirmation flow, a RAG stack, and a token-audit layer. Its `application.yml`
defaults are `enabled=true`, `actions.enabled=true`, `rag.enabled=true`, despite
comments in the same file stating it "ships dark", meaning an LLM with write access
to attendance, grades, homework, classes, students and announcements is on by
default against a third-party inference endpoint.

Two framings needed to be settled so they survive past the review conversation:
what the absent modules *are*, and what happens to the assistant.

## Decision

**1. The absent modules are unbuilt, not removed.**

`fees`, `messaging`, `reporting`, `audit`, and `hardening` are the remaining gates
in the build order recorded in `.claude/CLAUDE.md`, and none has been started. The
brief's instruction to remove all payment functionality applies only to `fees`,
which was never begun — there is nothing to remove. Any future document, estimate,
or customer commitment treats messaging, files, calendar, dashboards, and transport
as **greenfield work**, not as existing features needing repair.

**2. The assistant is frozen at its current scope.**

- It ships **disabled by default**: `assistant.enabled`, `assistant.actions.enabled`,
  and `assistant.rag.enabled` all default to `false`, with the misleading comments
  corrected. Enabling is an explicit per-environment opt-in.
- **Spring AI is the only engine.** The hand-written native gateways
  (`AnthropicLlmGateway`, `GeminiLlmGateway`, `DeepSeekLlmGateway` and their three
  `*ClientConfig` classes, plus `DeepSeekLlmGatewayTest`) are **deleted from the
  tree**, not left behind a flag. The `schoolbridge.assistant.engine` property is
  removed with them, as are `provider`, `api-key`, `gemini-api-key`,
  `deepseek-api-key`, and `deepseek-base-url` — provider credentials live under
  `spring.ai.*` only. `LlmGateway` stays as the seam; `SpringAiLlmGateway`
  conditions on `assistant.enabled` alone.
- **One primary provider plus one fallback**, selected through `spring.ai.model.chat`.
  The three-provider matrix (Anthropic / Gemini / DeepSeek) collapses.
- **Read-only tools only in v1.** Mutating tools — those that write attendance,
  grades, homework, classes, students, or announcements — are cut from the v1
  surface.
- No further assistant feature work until the P0 list below is closed.

**3. The P0 list in `PLATFORM_REVIEW.md` §14 is the pre-launch gate.**

Ship-blocking, in order: rotate the API key committed in `application.yml` and
remove every secret literal; delete the plaintext OTP log line in
`ParentAuthService`; make the outbox relay retry-safe and multi-instance-safe;
fix multi-child announcement acknowledgement; add platform-wide rate limiting with
a per-phone OTP cap; flip the assistant defaults; stand up CI.

## Why

The assistant was built to roughly 40% of the codebase while the features schools
actually buy on — 1:1 messaging, attachments, delivery reporting — do not exist.
Without messaging, the product is a broadcast tool, and schools already have
broadcast for free in WhatsApp groups. Continuing to invest in the assistant while
that gap stands is the clearest misallocation in the project, so the freeze is
about capacity, not about the assistant being bad code.

Shipping it disabled also retires three risks at once: student PII flowing to a
third-party inference endpoint under a key that was committed to git; an LLM
holding write access to attendance and grade records; and a second authorization
surface (the tool permission guard) that must be proven correct alongside the REST
one.

The native engine is **deleted rather than flagged off** because the flag currently
defaults the wrong way. `AssistantProperties.engine` is initialised to `"native"`,
and every native bean conditions on
`'${schoolbridge.assistant.engine:native}'.equals('native')` — so the *code*
default is the native path, and only `application.yml` overriding
`engine: ${ASSISTANT_ENGINE:springai}` keeps it unloaded. Any deployment that sets
`ASSISTANT_ENGINE=native`, or runs a config that omits the key, silently swaps the
whole inference path onto unmaintained gateways. A dead path that boots by default
is worse than no path. Deleting it also removes the second home for a provider key
in `application.yml`, which is where half of the §1.1 exposure lives.

Recording the scope correction as a decision rather than a review finding matters
because the misconception is externally sourced — it will resurface in the next
brief, estimate, or handoff unless there is a durable artifact contradicting it.

## Consequences

- The tool, RAG, conversation, and persona layers stay in the tree behind flags, so
  the freeze is reversible once the P0 list and the §2 feature gaps are closed.
  Reversing it supersedes this ADR rather than editing it. The native gateways are
  the exception: they are removed outright and recoverable only from git history.
- [ADR-004](ADR-004-spring-ai-pgvector-rag.md) (Spring AI + pgvector RAG, "ships
  dark") is now enforced rather than aspirational — its stated posture and the
  actual config defaults agree for the first time.
- [ADR-005](ADR-005-assistant-tool-architecture.md) still governs how tools are
  written; this ADR narrows *which* tools ship, not their shape.
- Deleting the native gateways removes `DeepSeekLlmGatewayTest` and the
  multi-provider wiring tests; `SpringAiEngineWiringTest` and
  `AssistantEnabledWiringTest` need updating for the new defaults, and the
  disabled-by-default path needs a test asserting no LLM bean loads.
- Two operational gotchas were recorded against `DeepSeekLlmGateway` and must
  survive its deletion, because both apply to the Spring AI OpenAI client that
  replaces it. First, on Windows JDK 23 the default JDK `HttpClient` request factory
  aborts and `SimpleClientHttpRequestFactory` is required — verify how Spring AI
  builds its factory before the native code goes, or local dev on Windows breaks
  with no obvious cause. Second, `base-url` must not include `/v1`: the Spring AI
  OpenAI client appends `/v1/chat/completions` itself, and a base URL carrying a
  path gets that path *replaced*, not extended. `spring.ai.openai.base-url` already
  gets this right while the native `deepseek-base-url` ends in `/v1` — do not carry
  the native value across. Both are written up in `docs/COMMON_MISTAKES.md`.
- Estimates for messaging, files, calendar, and dashboards start from zero. The
  `messaging` gate in particular has a schema dependency that must be resolved
  first: `announcements.body` and `homework_items.description` are AES-GCM
  encrypted and therefore unsearchable, and `messages.body` will inherit that
  unless a searchable projection is designed before the table exists
  (`PLATFORM_REVIEW.md` §5.3).
- Bus tracking remains out of scope, consistent with `SchoolBridge_SRS_v1.0.md`
  §1.4. If a launch customer forces it, the committed fallback is driver-tap status
  updates (*departed / arriving / arrived*) with push notifications — not live GPS,
  which carries a children's-location privacy surface disproportionate to v1.
