# Architecture Decision Records

Numbered, immutable once accepted — a later decision that reverses one of
these adds a new ADR that supersedes it rather than editing history.

| ADR | Title | Status |
|-----|-------|--------|
| [001](ADR-001-liquibase-spotless.md) | Liquibase forward-only migrations + Spotless google-java-format | Accepted |
| [002](ADR-002-tenant-isolation.md) | Tenant isolation via Hibernate `@Filter` + explicit `findById` override | Accepted |
| [003](ADR-003-rbac-aop-permissions.md) | `@RequirePermission` + AOP, DB-backed single-role permission model | Accepted |
| [004](ADR-004-spring-ai-pgvector-rag.md) | Spring AI + pgvector RAG, additive under `LlmGateway` | Accepted (ships dark) |
| [005](ADR-005-assistant-tool-architecture.md) | AI assistant tools as thin adapters over existing services | Accepted |
| [006](ADR-006-slash-style-action-paths.md) | Slash-style action paths, not AIP colon-verb paths | Accepted |
