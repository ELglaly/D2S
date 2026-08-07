# ADR-004: Spring AI + pgvector RAG, additive under `LlmGateway`

**Status:** Accepted (ships dark)

## Context

The assistant module originally called Anthropic directly
(`AnthropicLlmGateway`, OkHttp). We want (a) a provider-agnostic path via
Spring AI so switching/adding model providers doesn't touch orchestration
code, and (b) retrieval-augmented context (RAG) so the assistant can draw on
school-specific knowledge documents, without risking the existing
native-gateway behavior.

## Decision

- Single seam: `LlmGateway` interface. `SpringAiLlmGateway` (wraps Spring AI
  `ChatModel`) is a new implementation that slots in alongside the existing
  `AnthropicLlmGateway`, selected by `schoolbridge.assistant.engine=native|springai`
  (default `native`). The two are mutually exclusive
  (`@ConditionalOnExpression` on both sides).
- `internalToolExecutionEnabled=false` on the Spring AI side — the model
  only *selects* tools; the existing orchestrator still executes them, so
  the confirm-then-execute gate and RBAC checks are untouched regardless of
  engine.
- RAG is additive: `assistant_vector_store` (pgvector, stock Spring AI
  shape) + `assistant_documents` registry, `RagRetriever`
  (tenant-hard-scoped via `metadata.school_id`, best-effort — retrieval
  failure never breaks chat) and `ContextAugmenter` (untrusted-context
  section + prompt-injection guard header).
- Everything ships **dark**: `spring.ai.model.*=none` keeps Spring AI's
  eager auto-configuration inert, `schoolbridge.assistant.rag.enabled=false`
  keeps retrieval off, `engine=native` keeps the original gateway live.
  Flipping either is a config change, not a code change.

## Alternatives considered

- **Real Vertex AI embeddings now** — deferred. The
  `spring-ai-starter-model-vertex-ai-gemini` native stack (gRPC/netty-tcnative)
  crashes the shared Surefire fork on JDK 23/Windows; a placeholder
  `@ConditionalOnMissingBean` embedding model stands in until go-live, where
  it's a drop-in swap.
- **Postgres RLS for tenant isolation on the vector store** — deferred;
  can't be verified under Testcontainers (table owner bypasses RLS) and
  would need a tenant GUC threaded through ingestion. App-level filtering
  (`metadata->>'school_id'`) is the tested isolation control for now.

## Consequences

- Test Postgres must be the `pgvector/pgvector` image so
  `CREATE EXTENSION vector` in the RAG migration succeeds.
- Go-live requires: wire real embeddings, flip `engine=springai` and
  `rag.enabled=true` per environment, soak, then default — tracked as the
  deferred Phase 4/tail work, not a code-correctness gap.
