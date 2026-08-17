# SchoolBridge — Project Instructions for Claude

> Read me at the start of every SchoolBridge session. Then check the auto-memory index
> (`feedback_*.md` files) and skim `docs/COMMON_MISTAKES.md`.

## Operating Principles

### 1. Plan Mode Default
- Enter plan mode for ANY non-trivial task (3+ steps, cross-module change, or architectural decision)
- Write a detailed plan before code; reduce ambiguity upfront
- Plan also covers verification steps, not just building

### 2. Self-Improvement Loop
- After ANY correction from the user: save a `feedback_*.md` memory (see the auto-memory system) and,
  if the mistake is a concrete technical trap worth a permanent record, add an entry to
  `docs/COMMON_MISTAKES.md`
- Write rules for yourself that prevent the same mistake
- Review memory + `docs/COMMON_MISTAKES.md` at session start for the area you're about to touch

### 3. Verification Before Done
- Never mark a task complete without proving it works
- For new code: `mvnw.cmd -DskipTests compile` must pass; relevant tests must pass; if the change
  touches a `TenantEntity` or its repository, `TenantEntityArchUnitTest` must pass
- `mvn spotless:apply` before every commit (SpotBugs and Spotless are both hard build gates —
  `mvn verify` fails the build on either)
- Ask yourself: "Would a staff engineer approve this?"

### 4. Demand Elegance (balanced)
- For non-trivial changes: pause and ask "is there a simpler, more idiomatic Spring Boot way?"
- If a fix feels hacky: redesign with the lesson in mind
- Skip for obvious one-liners

## Core Principles
- **Simplicity First**: smallest change that solves the problem
- **No Laziness**: find the root cause, no temporary patches
- **Tenant isolation is sacred**: every tenant-scoped entity extends `TenantEntity`, and every
  repository on one overrides `findById` with an explicit `@Query` — the Hibernate `@Filter` does
  not apply to `EntityManager.find()` (see `docs/COMMON_MISTAKES.md` #1)

## Project Identity

| | |
|---|---|
| **Name** | SchoolBridge |
| **Type** | Multi-tenant school communication/administration REST API, with a parallel AI assistant |
| **Path** | `E:\D2L` |
| **Maven group** | `com.schoolbridge` |
| **Maven artifact** | `api` (module root `com.schoolbridge.api`) |
| **Base Java package** | `com.schoolbridge.api` |
| **Frontend** | None in this repo — backend-only API; Flutter mobile app is the primary client (see `docs/FLUTTER_APP_PLANNING_PROMPT.md`) |

## Domain in One Paragraph

Each tenant is a **school**. Every tenant-scoped row carries a `school_id`, enforced by a Hibernate
`@Filter` bound per-request from the authenticated principal, and reinforced at the database layer by
Postgres Row-Level Security. Teachers post announcements and homework, mark attendance, and record
grades; parents (linked to students) see a feed, acknowledge announcements, and get notified over
WhatsApp / push / SMS according to their own quiet-hours and per-category preferences. Every
tenant-scoped write and its cross-module side effect commit in the same transaction via an **outbox
row** — no dual-write — and a RabbitMQ consumer in `integrations` picks the event up asynchronously.
A parallel **AI assistant** module lets a user perform the same actions the REST API exposes through
natural-language tool calls, gated by the same `@RequirePermission` authorization path a real HTTP
request would go through (ships dark — off by default, see `ASSISTANT_ENABLED`).

## Current Phase

The module-by-module gated build (tenant → identity → classes → announcements → integrations →
attendance → homework → attachments → notifications → assistant) is complete — see `docs/HANDOFF_M5.md`
through `docs/HANDOFF_M9.md` for the module-by-module history. The project is now in **P0 pre-launch
remediation** (current branch `p0-remediation`) — see `docs/P0_REMEDIATION.md`: secrets/startup
validation, outbox retry safety, rate limiting, Row-Level Security on tenant tables, the attachment
pipeline, and notification preferences have landed; remaining P0 items are tracked in that doc.

## Tech Stack

| Component | Technology |
|---|---|
| Framework | Spring Boot **3.4.5** |
| Language | Java **21** (`pom.xml` `<java.version>`) |
| Build | Maven (`mvnw.cmd` on Windows) |
| Database | PostgreSQL + Liquibase migrations (forward-only, `db/changelog/db.changelog-master.yaml`) |
| Caching | Spring Cache + Redis (`spring-boot-starter-data-redis`) |
| Messaging | RabbitMQ (`spring-boot-starter-amqp`) — outbox pattern for cross-module events |
| Auth | JWT (`JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`), Spring Security, custom `@RequirePermission` + AOP |
| AI / Assistant | Spring AI (OpenAI-compatible + Anthropic), pgvector for RAG (`pgvector/pgvector:pg16`) |
| Resilience | Resilience4j |
| Observability | Micrometer, OpenTelemetry (`OTEL_EXPORTER_OTLP_ENDPOINT`) |
| External integrations | WhatsApp (Meta Cloud API), FCM push, SMS, RabbitMQ consumers |
| Storage | S3-compatible object storage (MinIO in dev/test) — presigned upload/download, MIME sniffing, AV scan |
| API docs | springdoc-openapi (OpenAPI 3) |
| Annotations | Lombok |
| Coverage | JaCoCo |
| Lint/format | Spotless (google-java-format) + SpotBugs — **both are hard build gates** |
| Tests | JUnit 5 + Testcontainers (Postgres/pgvector, RabbitMQ, Redis, MinIO) + ArchUnit + REST Assured |

## Module Map (14 modules)

Base package: `com.schoolbridge.api`. Package-by-feature, not package-by-layer — a module's entity,
repository, service, and controller live at the module root; `dto/` is a sub-package. Interface +
`*Impl` split for every service (`HomeworkService` / `HomeworkServiceImpl`).

```
com.schoolbridge.api
├── common          # TenantEntity base, ApiResponse envelope, audit, outbox, crypto (AES-GCM,
│                    # blind index), i18n, idempotency, tenancy filter plumbing, web/error handling
├── config          # ApplicationConfig, OpenApiConfig — cross-cutting Spring config
├── tenant          # School onboarding, tenant resolution
├── identity         # Users, roles, JWT auth, refresh tokens, device tokens, OTP, platform admin
├── classes           # Classrooms (SchoolClass), students, enrollments, parent-child links
├── subjects           # Subject catalog (per-school)
├── grades              # Grade records
├── announcements        # School/class announcements, targeting, acknowledgement
├── attendance             # Attendance records, absence alerts, reports
├── homework                # Homework items, recipients, reminders
├── attachments               # Presigned S3 upload/download, MIME sniffing, AV scan, retention
├── notifications               # Per-user quiet hours, per-category opt-out, channel order
├── integrations                 # WhatsApp / push / SMS adapters, RabbitMQ consumers, outbox dispatch
└── assistant                     # AI assistant: conversation, tool-calling, RAG, permission-gated
                                   # actions (ships dark)
```

**There is no `notification` (singular) module.** It's `notifications` (plural) — per-user
preferences (quiet hours, channel order, category opt-out). Actual dispatch to WhatsApp/FCM/SMS is
owned by `integrations`.

Dependency direction (never point backward):
```
tenant → identity → classes → announcements → integrations → attendance → homework
      → attachments → notifications → assistant
```
`common` and `config` are depended on by everything and depend on nothing module-specific.
`assistant` depends on the domain modules' **services**, never repositories, so a tool call and a
REST call go through the same authorization and business-rule path. `integrations` is the only
module allowed to talk to external systems — other modules publish outbox events instead.

See `.claude/rules/java/schoolbridge-modules.md` for sub-package detail per module.
See `.claude/rules/java/schoolbridge-api-map.md` for the REST surface.
See `.claude/rules/java/schoolbridge-domain-model.md` for entities and enums.
See `docs/ARCHITECTURE.md` for the full write path and `docs/adr/` for the *why* behind each choice.

## API Conventions

- All responses wrapped in a single `ApiResponse<T>` envelope, applied automatically by
  `ApiResponseBodyAdvice` — do not hand-wrap in a controller
- All request bodies validated with `@Valid`
- Mutating endpoints gated by `@RequirePermission` (DB-backed single-role permission model, AOP —
  see ADR-003); own-resource checks additionally use `@PreAuthorize` where cheap
- **Slash-style action paths only** — `/homework/{id}/publish`, never `/homework/{id}:publish`
  (colon-verb paths get percent-encoded by real clients and 404 — ADR-006, `docs/COMMON_MISTAKES.md` #6)
- Currency: store money as `NUMERIC`/`BigDecimal`, never `double`
- Time: store all timestamps as UTC `Instant`; convert to local zone at the edge
- Every user-facing message string needs both `messages_ar.properties` and `messages_en.properties`
  entries — i18n parity is a hard requirement

## Critical Rules

1. **Tenant `findById` bypass**: `Hibernate @Filter` only applies to queries (HQL/JPQL/criteria), not
   `EntityManager.find()`. Every `TenantEntity` repository must override `findById` with an explicit
   `@Query`. `TenantEntityArchUnitTest` enforces the `@Filter` annotation exists; it does **not**
   catch a missing `findById` override — add a cross-tenant isolation test for any new repo.
2. **RLS GUC empty-string trap**: `current_setting('app.current_tenant', true)::uuid` returns `''`
   (not NULL) after a reset on a pooled connection — wrap in `nullif(..., '')` to fail closed, not 500.
3. **Testcontainers connects as superuser**: superusers bypass RLS unconditionally, `FORCE ROW LEVEL
   SECURITY` doesn't subject them. RLS tests must `SET LOCAL ROLE` onto an unprivileged role inside
   the test transaction.
4. **`Map.of(...)` NPEs on outbox/audit payloads**: these carry nullable fields. Always build with
   `HashMap`/`LinkedHashMap`, never `Map.of(...)`.
5. **WhatsApp webhook**: verify the signature (`WebhookSignatureVerifier`) before trusting the
   payload; the endpoint must be explicitly public in `SecurityConfig`.
6. **Spotless strips an import added before its first use**: add the import and its usage in the
   same edit, or the `removeUnusedImports` post-write hook strips it and the next compile fails.
7. **SpotBugs flags `\n` inside `.formatted(...)`**: use `template.replace("{x}", v)` for multi-line
   templates instead.
8. **Windows build**: use `mvnw.cmd`, not `./mvnw`.
9. **Presigned PUT cannot cap upload size** (`content-length-range` is POST-only) — set
   `contentLength` on the `PutObjectRequest` before presigning instead.
10. **`S3Presigner` needs the same `endpointOverride`** as the `S3Client`, or presigned URLs are
    signed for the wrong host and fail with a 403 that looks like bad credentials.
11. **A stub in a fallback chain must report failure**: a no-op notification channel that claims
    success silently ends a first-match-wins dispatch walk — see `docs/COMMON_MISTAKES.md` #15.
12. **New FK to `users(id)`/`schools(id)`**: always `ON DELETE CASCADE`, or existing test teardown
    (`deleteAll()`) breaks with a `DataIntegrityViolationException`.

Full list with fixes: `docs/COMMON_MISTAKES.md`.

## Testing Infrastructure

- Base class: `AbstractIntegrationTest` (`src/test/java/com/schoolbridge/api/`) — **singleton
  container** pattern: Postgres (`pgvector/pgvector:pg16`), RabbitMQ, Redis, and MinIO are started
  once per JVM in a static initializer via `@ServiceConnection` and never torn down, so cached
  `@SpringBootTest` contexts stay valid across the whole run
- MinIO has no `@ServiceConnection` — its endpoint/credentials are pushed via
  `@DynamicPropertySource` because a presigned URL's signature covers the host, and the container's
  port is only known at runtime
- `TenantEntityArchUnitTest` enforces `@Filter` presence on tenant-scoped entities
- RLS tests run under an unprivileged role via `SET LOCAL ROLE` (`RlsTestRole` helper) — never assert
  isolation on the default (superuser) Testcontainers connection
- Test naming: `methodName_scenario_expectedBehavior` (e.g. `findById_existingOrder_returnsOrder`)
- Tests mirror `src/main/java` package structure under `src/test/java`

## Project General Instructions

- Always use the latest stable versions of dependencies
- Always write Java code as a Spring Boot application
- Always use Maven for dependency management
- Always create test cases for the generated code (positive and negative)
- Minimize the amount of code generated
- Maven artifact group `com.schoolbridge`; base package `com.schoolbridge.api`
- Lombok is a declared dependency but **not actually used anywhere in `src/main/java`** — entities
  and services use explicit hand-written constructors, getters, and rich domain methods (e.g.
  `HomeworkItem.publish()`, `.archive()`). Match that style; don't introduce `@Getter`/`@Builder`/
  `@RequiredArgsConstructor` into new code just because the dependency is on the classpath
- Prefer Java records for DTOs (request/response payloads); entities stay JPA classes
- Use semantic versioning
- Do NOT generate a CircleCI pipeline (project does not use CircleCI)
- Before making any edits, produce a written plan: (1) files you'll touch, (2) signatures/APIs that
  will change, (3) all call sites that need updates, (4) how you'll verify. Wait for my approval
  before editing.
- Before proposing any solution, list the hard constraints I've stated (things to AVOID) and confirm
  your proposal does not violate any of them. If unsure, ask.
