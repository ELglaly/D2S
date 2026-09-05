<div align="center">

# SchoolBridge API

### A secure, WhatsApp-first communication backbone for modern schools

Multi-tenant Spring Boot API for announcements, attendance, homework, grades, and dependable parent communication.

`Java 21` · `Spring Boot 3.4` · `PostgreSQL + pgvector` · `RabbitMQ` · `Redis` · `MinIO / S3`

</div>

---

## Purpose

SchoolBridge gives school teams one trusted backend for parent-facing operations: publish a notice, record attendance, assign homework, share a grade, or send a secure attachment. It is designed around tenant isolation, bilingual communication, and reliable delivery.

| Built for | What it enables |
| --- | --- |
| **School teams** | Manage schools, staff, rosters, subjects, announcements, attendance, homework, and grades. |
| **Parents** | Authenticate by phone and OTP; receive notices; acknowledge announcements; view child information. |
| **Platform operators** | Onboard tenants, operate integrations, observe health, and enforce least-privilege data access. |

## At a glance

| Area | Highlights |
| --- | --- |
| 🏫 Multi-tenancy | Hibernate tenant scoping plus PostgreSQL row-level security (RLS). |
| 🔐 Identity | JWT for staff; phone + OTP for parents; role- and permission-based authorization. |
| 📣 Communication | Announcements, attendance alerts, homework reminders, WhatsApp, SMS fallback, and push notifications. |
| 📎 Attachments | S3-compatible storage, presigned URLs, byte-level content checks, and production AV enforcement. |
| 🤖 Assistant | Optional tenant-aware AI assistant with tool calling and RAG; disabled by default. |
| 🌍 Language | English and Arabic message bundles for user-facing strings. |

## System architecture

```mermaid
flowchart LR
    subgraph Users[SchoolBridge users]
        Staff[School staff app]
        Parent[Parent app]
        Admin[Platform admin]
    end
    subgraph API[SchoolBridge API · Spring Boot]
        Security[Authentication & authorization]
        Domains[Feature modules]
        Outbox[Transactional outbox]
        Files[Attachment service]
    end
    subgraph Data[Private platform data]
        PG[(PostgreSQL + pgvector<br/>Liquibase · RLS)]
        Cache[(Redis)]
        MQ[RabbitMQ]
        ObjectStore[(MinIO / S3)]
    end
    subgraph Channels[Delivery channels]
        WA[WhatsApp Cloud API]
        SMS[SMS provider]
        FCM[Firebase Cloud Messaging]
    end
    Staff -->|JWT / HTTPS| Security
    Parent -->|Phone OTP / HTTPS| Security
    Admin -->|JWT / HTTPS| Security
    Security --> Domains
    Domains --> PG
    Domains --> Cache
    Domains --> Outbox
    Domains --> Files
    Files -->|Presigned URLs| ObjectStore
    Outbox --> MQ
    MQ --> WA
    MQ --> SMS
    MQ --> FCM
```

## Notification delivery

```mermaid
sequenceDiagram
    participant S as School staff
    participant A as SchoolBridge API
    participant D as PostgreSQL
    participant Q as RabbitMQ
    participant N as Notification worker
    participant W as WhatsApp
    participant F as SMS fallback
    S->>A: Publish announcement / record attendance
    A->>D: Commit domain change + outbox event
    A-->>S: Return successful API response
    A->>Q: Relay committed outbox event (production)
    Q->>N: Deliver notification job
    N->>W: Send approved WhatsApp template
    alt WhatsApp delivery fails repeatedly
        N->>F: Send SMS fallback
    end
```

## Feature map

```mermaid
mindmap
  root((SchoolBridge))
    Tenant platform
      Schools
      Settings
      Subscriptions
    Identity and access
      Staff JWT
      Parent phone OTP
      Roles and permissions
      Device tokens
    Academic operations
      Classes and enrollment
      Subjects and assignments
      Attendance
      Homework
      Grades
    Communication
      Announcements
      Parent acknowledgements
      WhatsApp SMS push
    Platform services
      Attachments
      Outbox
      AI assistant
      Observability
```

## Quick start

### Prerequisites

- JDK 21
- Maven 3.9+
- Docker Desktop or another Docker-compatible engine

### 1. Configure and start local services

Copy the environment template, complete the Docker Compose credentials that are intentionally blank, and start PostgreSQL, RabbitMQ, Redis, and MinIO.

```powershell
Copy-Item .env.example .env
docker compose up -d
```

### 2. Run the API

The `local` profile is the default. It uses disposable local development settings and stubs external messaging until provider credentials are supplied.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -B -ntp spring-boot:run -Dspring-boot.run.profiles=local
```

### 3. Explore it

| Service | Local address |
| --- | --- |
| API / Swagger UI | <http://localhost:8080/swagger-ui.html> |
| OpenAPI JSON | <http://localhost:8080/v3/api-docs> |
| Health | <http://localhost:8080/actuator/health> |
| Prometheus metrics | <http://localhost:8080/actuator/prometheus> |
| RabbitMQ management | <http://localhost:15672> |
| MinIO console | <http://localhost:9001> |

> [!IMPORTANT]
> The local profile’s committed crypto defaults are for disposable development data only. Never point it at real data. Production requires environment-supplied keys, credentials, and separate database roles.

## API domains

All application endpoints are versioned below `/api/v1`.

| Domain | Covers |
| --- | --- |
| `tenant` | School onboarding, status, subscriptions, and per-school settings. |
| `identity` | Staff users, roles, permissions, JWT login, parent OTP login, and device tokens. |
| `classes` | Classes, students, enrollment, parent-child links, and roster import. |
| `subjects` | Subject catalog, class assignments, and teacher assignments. |
| `announcements` | Publishing, targeting, scheduling, delivery tracking, and acknowledgement. |
| `attendance` | Roster marking, history, absence/late alerts, and parent responses. |
| `homework` / `grades` | Homework, reminders, due dates, grade records, and parent read access. |
| `attachments` | Authorized presigned upload/download and attachment lifecycle. |
| `assistant` | Optional conversations, tool use, knowledge ingestion, and tenant settings. |

## Local topology

```mermaid
flowchart TB
    Dev[Developer machine] --> App[SchoolBridge API :8080]
    App --> Postgres[(PostgreSQL + pgvector<br/>host :5434)]
    App --> Redis[(Redis<br/>host :6379)]
    App --> Rabbit[RabbitMQ<br/>host :5672]
    App --> Minio[MinIO<br/>API :9000 · Console :9001]
    Rabbit --> RUI[RabbitMQ UI :15672]
```

Stop the local services with `docker compose down`.

## Profiles and configuration

| Profile | Intended use | Key behavior |
| --- | --- | --- |
| `local` | Day-to-day development | Docker Compose dependencies, disposable development crypto, debug logging, and stubbed messaging unless configured. |
| `test` | Automated tests | Testcontainers starts PostgreSQL, RabbitMQ, Redis, and MinIO; sweepers and rate limits are disabled for determinism. |
| `prod` | Deployment | Environment-only secrets, outbox relay enabled, Swagger disabled, AV scanning required, and separate runtime/migration database users. |

Copy `.env.example` to `.env` to see the complete infrastructure and secrets contract. Production particularly needs `AES_KEY`, `BLIND_INDEX_KEY`, `DB_*`, `DB_MIGRATION_*`, `STORAGE_*`, messaging credentials, and—only when approved—assistant provider credentials.


```

## Security model

```mermaid
flowchart LR
    Request[Incoming request] --> Auth{Authenticated?}
    Auth -- No --> Reject[401 / OTP challenge]
    Auth -- Yes --> Permission{Allowed by role<br/>and permission?}
    Permission -- No --> Forbidden[403]
    Permission -- Yes --> Tenant[Resolve school tenant]
    Tenant --> AppFilter[Hibernate tenant filter]
    AppFilter --> RLS[PostgreSQL row-level security]
    RLS --> Data[Only tenant-scoped data]
```

- Tenant-owned entities are isolated by application-level filtering and PostgreSQL RLS policies.
- Production uses separate runtime and Liquibase database roles; startup validation rejects an app account that can bypass RLS.
- Clients get short-lived attachment URLs only after authorization. File bytes are sniffed against an allowlist, and AV scanning is mandatory in production.
- Secrets are environment-supplied; error responses exclude messages and stack traces.
- Redis backs blanket traffic limits plus targeted login and OTP quotas.

## Optional AI assistant

The assistant is intentionally opt-in:

```dotenv
ASSISTANT_ENABLED=false
ASSISTANT_ACTIONS_ENABLED=false
ASSISTANT_RAG_ENABLED=false
SPRING_AI_CHAT=none
```

When explicitly enabled, it provides tenant-aware conversations, constrained tool calling, confirmation flows, and pgvector-backed knowledge retrieval. Review data-processing implications before sending school data to a third-party inference provider.

## Build and test

```shell
# Fast compilation check
mvn -B -ntp -DskipTests compile

# Full completion gate: tests, formatting, static analysis, and coverage
mvn -B -ntp verify
```

`mvn verify` runs the test suite, Spotless (Google Java Format, 2-space indentation), SpotBugs, and JaCoCo reporting. Integration tests use Testcontainers and validate real Liquibase migrations, authorization, tenant isolation, RLS, notifications, attachment safety, and assistant guardrails.

## Project layout

```text
src/
├── main/java/com/schoolbridge/api/
│   ├── announcements/  assistant/  attachments/  attendance/
│   ├── classes/        common/     grades/       homework/
│   ├── identity/       integrations/ subjects/    tenant/
│   └── config/
├── main/resources/
│   ├── db/changelog/          # Liquibase migrations
│   ├── application*.yml       # Profile configuration
│   └── messages_*.properties  # Arabic and English messages
└── test/                      # Unit, integration, RLS, and architecture tests
```

## Contributing

1. Keep work scoped to a package-by-feature module.
2. Add or update tests with behavior changes.
3. Run `mvn -B -ntp verify` before opening a change.
4. Do not commit secrets, production keys, or real school data.

<div align="center">

Built for dependable school-to-parent communication — with privacy and tenant safety at the center.

</div>
