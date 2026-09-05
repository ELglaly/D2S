# SchoolBridge API

SchoolBridge is a multi-tenant backend for school-to-parent communication. It supports
school operations, parent authentication, announcements, attendance, homework, grades,
attachments, and notification delivery.

Each tenant-owned record is isolated by a Hibernate filter and PostgreSQL row-level
security. Arabic and English user-facing strings are resolved from message bundles.

## Features

- Tenant-isolated school, staff, student, class, subject, attendance, homework, and grade data
- JWT authentication for staff and phone/OTP authentication for parents
- Role and permission-based authorization
- Announcements, attendance alerts, homework reminders, WhatsApp delivery, SMS fallback, and push notifications
- S3-compatible attachments with content checks and optional anti-virus scanning
- Optional assistant with tool calling and retrieval-augmented responses, disabled by default

## Tech Stack

| | |
|---|---|
| Runtime | Java 21, Spring Boot 3.4 |
| Data | PostgreSQL 16 (+ pgvector), Liquibase, Redis, RabbitMQ |
| Auth | JWT for staff, phone + OTP for parents, DB-backed RBAC |
| Integrations | WhatsApp Cloud API (Meta), SMS fallback, FCM |
| Observability | Actuator, Micrometer/Prometheus, OpenTelemetry |
| Tests | JUnit 5, Testcontainers, RestAssured |

## Modules

Package-by-feature under `com.schoolbridge.api`:

| Module | Responsibility |
|---|---|
| `common` | Shared utilities, base entities, tenancy, crypto, outbox, audit, error handling |
| `config` | Spring configuration and app wiring |
| `tenant` | School onboarding, per-school settings |
| `identity` | Users, roles, JWT + OTP authentication, device tokens |
| `classes` | Classrooms, students, parent↔child links |
| `subjects` | Per-school subject catalog |
| `grades` | Grade records |
| `announcements` | School announcements, targeting, acknowledgement |
| `attendance` | Attendance records, absence alerting, reports |
| `homework` | Homework items, recipients, reminders |
| `integrations` | WhatsApp / SMS / push adapters, outbox consumers |
| `assistant` | AI assistant (tool-calling + RAG) — **off by default**, see ADR-007 |

## Quick start

Requires JDK 21, Maven 3.9+, and a Docker-compatible engine.

Copy `.env.example` to `.env` and provide the required datastore credentials before
starting Docker Compose. Production additionally requires encryption, database,
messaging, and storage credentials through environment variables.

```powershell
Copy-Item .env.example .env
docker compose up -d
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -B -ntp spring-boot:run -Dspring-boot.run.profiles=local
```

The `prod` profile requires `AES_KEY` and `BLIND_INDEX_KEY`; it will not start without
them. The `local` profile has disposable development defaults so a fresh checkout can
start against the local Docker database. Do not reuse those defaults for real data;
generate throwaway values with `openssl rand -base64 32` when needed.

The assistant is disabled by default. Enable it only after configuring a supported
provider and reviewing the data that may be sent to that provider.

API docs at `http://localhost:8080/swagger-ui.html` (disabled in `prod`).

## Building

```shell
mvn -B -ntp -DskipTests compile   # fast compile check
mvn -B -ntp verify                # full build: tests + Spotless + SpotBugs
```

Spotless (google-java-format, 2-space indent) and SpotBugs are **hard gates**, not
advisory. `mvn -B -ntp verify` must be green before any module change is considered
done.

## Environment Profiles

| Profile | Assumes |
|---|---|
| `local` | Everything from `docker-compose.yml`; dev crypto keys inline; message clients stubbed unless credentials are supplied |
| `test` | Testcontainers-provided Postgres/Redis/RabbitMQ; rate limiting and sweepers disabled so tests are deterministic |
| `prod` | All secrets from the environment; Swagger off; **two DB roles** — see the deployment configuration and CI workflow. |

## Tests

Integration tests extend `AbstractIntegrationTest`, which starts Postgres, RabbitMQ
and Redis once per JVM via the Testcontainers singleton-container pattern and runs
every Liquibase changeset against real PostgreSQL on each boot. That is also what
validates the changelog — there is no separate migration lint step.

Cross-tenant isolation is asserted per repository, plus an ArchUnit rule that fails
the build if a `TenantEntity` repository is missing its `findById` override, plus
`TenantRlsIntegrationTest` which proves the database policies hold for an
unprivileged role.

## Database

PostgreSQL is the primary database. Liquibase applies migrations from
`src/main/resources/db/changelog`; local Docker Compose starts PostgreSQL with pgvector,
Redis, RabbitMQ, and MinIO.

## Docker

Start local dependencies with `docker compose up -d` and stop them with
`docker compose down`.

## Project Structure

```text
src/
  main/
  test/
.env.example
docker-compose.yml
Dockerfile
pom.xml
README.md
```

## Security

- JWT and OTP authentication
- Role and permission-based authorization
- PostgreSQL row-level security for tenant data
- Environment-based credentials and encryption keys
- Presigned attachment URLs with type validation and production anti-virus enforcement
