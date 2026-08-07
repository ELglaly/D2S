# SchoolBridge — Architecture

> Companion to `.claude/CLAUDE.md` (gotchas, build commands, gate order). This
> doc covers the shape of the system: stack, modules, dependency direction,
> folder layout. See `docs/adr/` for *why* key decisions were made.

## 1. System overview

SchoolBridge is a multi-tenant school communication/administration API. Each
tenant is a **school**; all tenant-scoped data is row-isolated by `school_id`
under a Hibernate `@Filter`, bound per-request from the authenticated
principal. A parallel AI assistant module lets users perform the same
actions the REST API exposes via natural-language tool calls, gated by the
same permission system as the HTTP endpoints.

## 2. Tech stack

| Concern              | Choice                                                        |
|-----------------------|----------------------------------------------------------------|
| Language / runtime    | Java 21, Spring Boot 3.3                                       |
| Persistence            | PostgreSQL, Spring Data JPA, Liquibase (forward-only)          |
| Migrations             | Liquibase YAML, `db/changelog/db.changelog-master.yaml`        |
| Caching                | Spring Cache + Caffeine (local), Redis (shared)                |
| Messaging               | RabbitMQ (Spring AMQP) — outbox pattern for cross-module events |
| Auth                    | JWT (jjwt, RS256), Spring Security, custom `@RequirePermission` + AOP |
| AI / Assistant          | Spring AI (Anthropic + OpenAI starters), pgvector for RAG, Vertex AI |
| Resilience               | Resilience4j (circuit breaker / retry, reactor variant)        |
| Observability            | Micrometer + Prometheus, OpenTelemetry tracing, logstash JSON logs |
| External integrations    | WhatsApp (Meta Cloud API), FCM push, SMS (stubbed), RabbitMQ consumers |
| API docs                 | springdoc-openapi (OpenAPI 3), `docs/api/openapi.{json,yaml}`  |
| Testing                  | JUnit 5, Testcontainers, REST Assured, WireMock, ArchUnit       |
| Build/format/lint        | Maven, Spotless (google-java-format), SpotBugs, JaCoCo          |

## 3. Module map

Package root: `com.schoolbridge.api`

| Module          | Responsibility                                                     |
|------------------|----------------------------------------------------------------------|
| `common`          | Shared base entities (`TenantEntity`), response envelope, audit, outbox, crypto (AES-GCM, blind index), i18n, idempotency, tenancy filter plumbing, web/error handling |
| `config`           | `ApplicationConfig`, `OpenApiConfig` — cross-cutting Spring config  |
| `tenant`            | School onboarding, tenant resolution                                |
| `identity`           | Users, roles, JWT auth, refresh tokens, device tokens, OTP, platform admin |
| `classes`             | Classrooms (`SchoolClass`), students, enrollments, parent-child links |
| `subjects`             | Subject catalog (per-school)                                        |
| `grades`                | Grade records                                                       |
| `announcements`          | School/class announcements, targeting, acknowledgement              |
| `attendance`              | Attendance records, absence alerts, reports                         |
| `homework`                 | Homework items, recipients, reminders (**current gate**)            |
| `integrations`               | WhatsApp / push / SMS adapters, RabbitMQ consumers, outbox dispatch  |
| `assistant`                   | AI assistant: conversation, tool-calling, RAG, permission-gated actions |

Modules **not yet built** per the gate order in `.claude/CLAUDE.md`: fees,
messaging, reporting, audit, hardening.

### `common` sub-packages

`audit`, `crypto`, `error`, `i18n`, `idempotency`, `persistence` (base
entities), `security` (JWT, `@RequirePermission`, AOP aspect,
`PermissionsHelper`), `tenancy` (tenant filter/context), `web` (response
envelope, `ResponseBodyAdvice`).

### `assistant` sub-packages

`audit`, `cache`, `confirm` (destructive-action confirmation flow), `dto`,
`llm` (+ `llm/springai` provider adapter), `rag`, `settings`, `conversation`,
and `tools/<domain>` — one package per business domain
(`announcements`, `attendance`, `classes`, `grades`, `homework`, `parents`,
`read`, `staff`, `student`, `subjects`, `support`, `action`). Each tool is a
thin adapter over an existing service — see
`docs/adr/ADR-005-assistant-tool-architecture.md`.

## 4. Dependency direction

```
tenant → identity → classes → announcements → integrations → attendance
      → homework → (fees → messaging → reporting → audit → hardening)
```

`common` and `config` are depended on by everything and depend on nothing
module-specific. `assistant` depends on the domain modules' *services*
(never repositories directly) so a tool call and a REST call go through the
same authorization and business-rule path. `integrations` is the only module
allowed to talk to external systems (WhatsApp, FCM, SMS); other modules
publish outbox events instead of calling adapters directly.

Never introduce a dependency that points backward against the gate order
(e.g. `attendance` must not depend on `homework`).

## 5. Request flow (typical write)

```
Controller → @RequirePermission (AOP aspect) → Service → Repository (Tenant @Filter)
                                                     ↳ Outbox row (same tx)
                                                          ↳ RabbitOutboxPublisher → integrations consumer
```

Every tenant-scoped write and its outbox row commit in the same transaction
(outbox pattern — no dual-write). Consumers in `integrations/rabbit` pick up
outbox events asynchronously and dispatch to WhatsApp/push/SMS.

## 6. Folder structure conventions

- Package-by-feature (module), not package-by-layer. A module's entity,
  repository, service, and controller live at the module root; `dto/` is a
  sub-package.
- One public top-level type per file.
- Interface + `*Impl` split for services (`HomeworkService` /
  `HomeworkServiceImpl`) — enables mocking in tests without a mocking
  framework touching final classes.
- Tests mirror `src/main/java` package structure under `src/test/java`.

## 7. Key architectural decisions

See `docs/adr/`:

- ADR-001 — Liquibase forward-only + Spotless google-java-format
- ADR-002 — Tenant isolation via Hibernate `@Filter` + explicit `findById` override
- ADR-003 — `@RequirePermission` + AOP, DB-backed single-role permission model
- ADR-004 — Spring AI + pgvector RAG (ships dark)
- ADR-005 — AI assistant tool-calling architecture (thin adapters over services)
- ADR-006 — Slash-style action paths, no `:verb` (AIP colon paths don't survive clients)
