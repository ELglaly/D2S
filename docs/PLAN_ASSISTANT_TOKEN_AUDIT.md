# Assistant Token-Optimization Plan (Spring AI path only)

> **Status (2026-06-18):** Phase A ✅ · Phase B ✅ · Phase C1 (catalog gating) ✅ ·
> Phase C2 (rolling summarization) **deferred** — B2's window trim covers typical short threads;
> C2's recurring LLM-summary cost + migration not justified until long multi-topic threads appear in
> usage. All shipped behind no flag; 111/111 assistant tests green.

> Scope guard: every change in this plan operates on the **Spring AI** gateway and the
> provider-agnostic orchestrator/RAG/tool layers. The native gateways
> (`AnthropicLlmGateway`, `GeminiLlmGateway`, `DeepSeekLlmGateway`) are **slated for removal**
> and MUST NOT be modified or relied upon. Do not hand-roll any provider SDK feature
> (e.g. Anthropic `cache_control`) — route everything through Spring AI `ChatModel` /
> `ToolCallingChatOptions` so it survives once `engine=native` is deleted.

## Runtime baseline (verified)

- Spring AI `1.0.1` (`pom.xml:35`). Starters on classpath: anthropic, openai, pgvector.
- Active path: `engine=springai` (`application.yml:183`) → `SpringAiLlmGateway` →
  `spring.ai.model.chat=openai` → NVIDIA NIM / DeepSeek (OpenAI-compatible, `application.yml:22-37`).
- `model=deepseek-ai/deepseek-v4-flash`, `max-tokens=1024`, `max-tool-iterations=4`,
  `max-history-messages=40`, `rag.enabled=true topK=5 max-context-chars=6000`.
- Orchestrators: `AssistantServiceImpl` (`/ask`) and `ConversationChatService` (SSE) —
  both build `LlmRequest(system, history, tools, model, maxTokens)` and call the gateway
  in a loop up to `maxToolIterations`.
- `SpringAiLlmGateway.internalToolExecutionEnabled=false` → orchestrator owns the loop, so
  the full tool catalog + system are re-sent on **every** iteration.

## Measured catalog (the real cost)

- 51 tools total (32 ACTION, 19 READ). Role visibility: **SCHOOL_ADMIN 41**, TEACHER 17, PARENT 9.
- Tool descriptions already lean: avg 59 chars (~15 tok), total ~575 tok. **Do not waste effort trimming descriptions.**
- Cost driver = **tool count × schema props** (~221 props, avg 5.4/tool) ⇒ SA tool block ≈ **5.2k tok**, re-sent each iteration.
- Per SA `/ask` turn (1 tool call, 2 iterations): ~14.3k input tok, ~47% duplicate re-send. Worst case (4 iter): ~30k input, ~67% waste.

## Strategy

The OpenAI-compatible provider (DeepSeek/NIM) does **automatic prefix caching**: identical
leading request content across calls is billed at a steep discount. Today the cache is
defeated because RAG text is appended to the **end of the system prompt**, so the system
block changes every query, and tool order is non-deterministic. The plan makes the
**system + tool prefix byte-stable** and pushes all per-turn variation (RAG, user input,
tool results) to the **tail**, then shrinks the variable parts.

All levers live in Spring-AI-shared code; none touch native gateways.

---

## Phase A — Day 1 (pure wins, no flag)

### A1. Stable system + tool prefix for automatic caching  ★ highest impact
- **Files:** `rag/ContextAugmenter.java`, `AssistantServiceImpl.java`, `conversation/ConversationChatService.java`, `llm/springai/SpringAiLlmGateway.java`.
- **Change:** stop appending RAG into the system string. Keep `system` = static persona+guardrails only. Deliver retrieved context as a **trailing message** (a `UserMessage` placed right before/with the user turn).
  - Add `LlmMessage` for the context block instead of `augmenter.augment(system, chunks)`.
  - In `SpringAiLlmGateway.toPrompt` the `SystemMessage(request.system())` then becomes invariant across turns and iterations.
- **Tool order:** make `ToolRegistry.toolsFor` return a deterministic order (sort by `name()`), so the serialized tool array is byte-identical across iterations.
- **Why it saves:** iterations 2..N of every turn reuse the cached system+tool prefix; only the growing tail is full-price.
- **Target:** −50–70% input on multi-iteration turns (provider cache-hit dependent).
- **Risk:** low. Behavior identical; only message placement changes. Verify RAG still reaches the model (assert in existing RAG test).

### A2. `@JsonInclude(NON_NULL)` on `ToolResult`
- **File:** `tools/ToolResult.java`.
- **Change:** annotate the record so OK results don't serialize `"message":null` and error/clarify don't serialize `"data":null`. Serialized in `*ServiceImpl.serialize(...)`.
- **Target:** small but every tool_result; zero risk.

### A3. RAG retrieval knobs
- **File:** `application.yml` (`schoolbridge.assistant.rag`).
- **Change:** `top-k 5→3`, `max-context-chars 6000→3000`, `min-score 0.65→0.70`.
- **Target:** ~750 tok/iteration; lower-quality chunks dropped.
- **Risk:** low; tune `min-score` against the RAG eval set if one exists.

### A4. Iteration cap
- **File:** `application.yml`.
- **Change:** `max-tool-iterations 4→3`. Most flows resolve in ≤2; caps worst-case re-send.
- **Risk:** low-medium. Keep the existing `assistant.error.max_iterations` fallback.

**Phase A measurement:** read `inputTokens`/`outputTokens` already captured in response metadata
(`AssistantServiceImpl.metadata`, persisted via `ConversationStore.appendAssistant`). Record
mean input tokens/turn before and after on a fixed prompt set.

---

## Phase B — Week 1

### B1. Compact tool-result projections  ★
- **Files:** each READ tool under `tools/**` + a shared `view` helper; `ToolResult.data` payloads.
- **Problem:** `mapper.writeValueAsString(result)` serializes full domain DTOs/entities; the model only needs human-facing fields (names, dates, status) and is forbidden ids (`SystemPrompt.java:27`).
- **Change:** each tool returns a slim record (e.g. `record AttendanceLine(String date, String status)`) instead of the full service DTO. Cap collections (return first N + `"…M more"`).
- **Target:** 40–70% per tool_result (300–1500 tok on list-heavy tools: `list_homework`, `get_class_grades`, `get_class_attendance`).
- **Risk:** medium — ensure the model still gets every field it needs to answer. Cover with per-tool tests asserting the projected shape.

### B2. History window trim
- **Files:** `application.yml`, `conversation/ConversationStore.loadHistory`.
- **Change:** `max-history-messages 40→16`.
- **Target:** large on long conversations (each new turn currently replays up to 40 stored messages).
- **Risk:** low-medium; long-thread context loss — mitigated by B3 / Phase C.

---

## Phase C — Month 1

### C1. Tool-catalog gating  ★ (biggest single input cut for SA)
- **Files:** `tools/Tool.java` (+ `ToolKind`), `tools/ToolRegistry.java`, both orchestrators, `SpringAiLlmGateway.toToolCallbacks`.
- **Change:** add a `domain()` to `Tool` (grades, attendance, homework, classes, subjects, announcements, parents). Before the model loop, select the likely domain(s) from the user message (cheap keyword/classifier) and pass only those tool callbacks. Provide a fallback "expand catalog" round if the model asks for something not loaded.
- **Target:** SA catalog ~41→≤10 tools ⇒ ~3.5k tok/iteration.
- **Risk:** medium — intent misroute drops a needed tool. Mitigate with the fallback round + metrics on fallback rate.

### C2. Rolling history summarization
- **Files:** `conversation/ConversationStore`, new summary column/row, orchestrators.
- **Change:** once a thread exceeds the window, summarize older turns once, store the summary, and prepend it instead of replaying raw turns.
- **Target:** large on long threads.
- **Risk:** medium — summary quality; keep last K turns verbatim plus the summary.

---

## Explicitly out of scope

- `AnthropicLlmGateway`, `GeminiLlmGateway`, `DeepSeekLlmGateway` (native, being removed) — no edits.
- Native Anthropic `cache_control` / provider SDK calls — replaced by A1 prefix-stability so the win survives provider/native removal.
- Structured-output / JSON-schema response format — not used in this module.

## Rollout / verification

1. Land Phase A behind no flag (pure wins). Compare mean input tok/turn on a fixed prompt set via existing usage metadata.
2. Land B1/B2; add per-tool projection tests + a long-thread history test.
3. Land C1 last with a fallback-round and a metric on misroute/fallback rate before tightening.
4. Keep `mvn -B -ntp verify` green at each phase (gated build order).

## Quick-win table

| Pri | Change | Phase | Effort | Input-token cut | Risk |
|-----|--------|-------|--------|-----------------|------|
| 1 | Stable system+tool prefix / RAG→trailing msg | A1 | 1d | 50–70% multi-iter | Low |
| 2 | `@JsonInclude(NON_NULL)` ToolResult | A2 | 1h | small/result | None |
| 3 | RAG topK 5→3, ctx 6000→3000, score→0.70 | A3 | 1h | ~750/iter | Low |
| 4 | maxToolIterations 4→3 | A4 | 5m | caps worst case | Low-Med |
| 5 | Compact tool-result projections | B1 | 1w | 40–70%/result | Med |
| 6 | History 40→16 | B2 | 1h | large long-thread | Low-Med |
| 7 | Tool-catalog gating | C1 | 1w | ~3.5k/iter | Med |
| 8 | Rolling history summarization | C2 | 1mo | large long-thread | Med |
