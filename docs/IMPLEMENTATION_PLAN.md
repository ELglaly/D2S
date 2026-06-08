# SchoolBridge Backend — Comprehensive Implementation Plan

**Version:** 1.0 · **Date:** 2026-05-26 · **Source:** `SchoolBridge_SRS_v1.0.md` + locked-in architecture brief
**Status of code:** Module 1 (project skeleton) already built & compiling. This plan covers the whole MVP backend.

---

## 0. How this plan relates to existing code

Module 1 (skeleton) is **done**: Maven build (Boot 3.3.5 / Java 21), profiles, Liquibase baseline, Docker/compose, JSON logging, i18n bundles, Testcontainers smoke test. Decisions already locked:

- **Migrations: Liquibase** (not Flyway — spec was self-contradictory).
- **Build quality: Spotless + SpotBugs now; Error Prone deferred** (JDK 23 host, target 21).
- **Strict module-by-module delivery** with plan → confirm → code per module.

This document is the master reference; each module still gets its own focused mini-plan before coding.

---

## 1. Architecture & Design Decisions

### 1.1 Architectural style — **Modular monolith, feature-sliced, with light hexagonal seams at integration boundaries**

| Option | Verdict |
|---|---|
| Microservices | **Rejected for MVP.** SRS NFR-P3 (100k students / 50k DAU on a *single* cluster) is comfortably a monolith. Microservices add ops cost, distributed-tx complexity, and slow a 1-team MVP. |
| Layer-based monolith | Rejected. Encourages anemic cross-feature coupling. |
| **Feature-sliced modular monolith** | **Chosen.** One package per bounded context (tenant, identity, classes, attendance…), each self-contained (controller→service→repo→entity→dto). Cross-feature calls only via public service interfaces or domain events. Clean seam to split into services later if ever needed. |
| Hexagonal (ports/adapters) | **Applied selectively** at external boundaries: WhatsApp, SMS, Email, S3. Each is a port (interface) with a real adapter + a fake for tests. Internal CRUD features stay pragmatic Spring layering (no over-abstraction). |

**Cross-cutting patterns:**
- **Multi-tenancy:** shared schema + `school_id` discriminator, enforced by Hibernate `@Filter` driven from `TenantContext`. Every tenant repo has a cross-tenant invisibility test.
- **Transactional Outbox** for every mutation that triggers an external message (attendance alert, announcement, fee reminder). A relay polls `outbox_event` → RabbitMQ. Exactly-once-from-DB.
- **Domain events** via `@TransactionalEventListener(AFTER_COMMIT)` for in-process reactions; outbox for cross-process/external.
- **Idempotency** via `Idempotency-Key` header on all writes (Redis-backed cache, 24h).

### 1.2 Package structure (feature-based) — confirmed from brief §5

```
com.schoolbridge.api
├── SchoolBridgeApplication
├── common/{tenancy, security, error, audit, i18n, idempotency, crypto, web, outbox}
├── tenant, identity, classes, students
├── announcements, homework, attendance, fees, messaging
├── reporting, notifications
└── integrations/{whatsapp, sms, storage}
```
Each feature folder: `*Controller, *Service (+Impl), *Repository, *<Entity>.java, dto/, events/, internal/`.
**Naming:** entities = noun (`Announcement`), DTOs = `Create*Request`/`*Response`, events = `*Event`, mappers = `*Mapper`, value types = `SchoolId`, `Money`, `PhoneNumber`.

### 1.3 Technology stack (locked, with reasoning)

| Layer | Choice | Reason |
|---|---|---|
| Java / Boot | 21 LTS / 3.3.5 | LTS; virtual threads for blocking RestClient calls |
| Persistence | Spring Data JPA + Hibernate 6.5 | `@Filter` tenancy, `@UuidGenerator(TIME)` UUIDv7-ish |
| DB | PostgreSQL 16 | JSONB (outbox/audit), partial indexes, pgcrypto |
| Migrations | Liquibase | locked-in; YAML master + SQL changesets |
| Cache/tokens | Redis (Lettuce) | parent opaque tokens, OTP, idempotency, rate-limit buckets |
| Broker | RabbitMQ (Spring AMQP) | topic exchanges + per-consumer DLQ |
| Object store | S3-compatible (AWS SDK v2 / MinIO local) | attachments, exports, pre-signed URLs |
| HTTP out | RestClient + Resilience4j | sync + circuit breaker/retry for WhatsApp/SMS |
| Security | Spring Security 6, RS256 JWT (staff), opaque parent tokens | per brief |
| Mapping | ModelMapper | locked-in |
| Docs | springdoc-openapi 2.6 | OpenAPI 3.1 |
| Observability | Micrometer + OTel + logstash JSON logs | structured logs w/ traceId/schoolId/userId |
| Tests | JUnit5, AssertJ, Mockito, Testcontainers, RestAssured | per brief |

---

## 2. Domain Model & JPA Entities

### 2.1 Value objects (embeddable / converters)
- `SchoolId, UserId, StudentId, ClassId` — typed UUID wrappers (record + `AttributeConverter`) to kill ID-mixing bugs.
- `Money` — `@Embeddable(amount: BigDecimal(19,4), currency: String(3))`.
- `PhoneNumber` — E.164-validated; stored **encrypted** (see crypto converter).
- `DateRange`, `QuietHours(start, end)` embeddables.

### 2.2 Enums
`Role{SUPER_ADMIN, SCHOOL_ADMIN, TEACHER, PARENT}`, `UserStatus{ACTIVE, SUSPENDED}`, `SubscriptionTier{BASIC, STANDARD, PREMIUM}`, `RelationshipType{MOTHER, FATHER, GUARDIAN}`, `AttendanceStatus{PRESENT, ABSENT, LATE, EXCUSED}`, `AnnouncementScope{SCHOOL, GRADE, CLASS, CUSTOM}`, `AnnouncementStatus{DRAFT, SCHEDULED, SENDING, SENT, RECALLED}`, `DeliveryStatus{QUEUED, SENT, DELIVERED, READ, FAILED}`, `Language{AR, EN}`, `FeeStatus{PENDING, PARTIAL, PAID, OVERDUE}`, `PaymentMethod{CASH, BANK_TRANSFER, CARD, OTHER}`, `ConversationStatus{OPEN, CLOSED}`, `MessageDirection{INBOUND, OUTBOUND}`, `MessageStatus{QUEUED, SENT, DELIVERED, READ, FAILED}`, `OutboxStatus{PENDING, PUBLISHED, FAILED}`, `NotificationChannel{WHATSAPP, SMS, EMAIL}`, `MessageReportStatus{OPEN, REVIEWED, DISMISSED}`.

### 2.3 Aggregates & entities (fields → key annotations)

**Tenant context**
- `School` *(tenant root, NOT tenant-filtered itself)* — `id, name, country, timezone, locale, subscriptionTier, status, createdAt`. `@OneToOne SchoolSettings`.
- `SchoolSettings` — `schoolId, quietHours(embedded), homeworkReminderEnabled, homeworkReminderTime, feeReminderOffsets(int[]/jsonb), defaultLanguage, wabaPhoneNumberId, smsFallbackEnabled`.

**Identity**
- `User` — `id, schoolId(nullable for SUPER_ADMIN), role, name(enc), email(unique per school), phone(enc, indexed via blind-index hash), passwordHash(null for parents), status, createdAt`. Tenant-filtered (except super-admin rows).
- `RefreshToken` — `id, userId, tokenHash, expiresAt, revokedAt, createdAt`.
- *(Redis)* `OtpTicket{ticketId, schoolId, phoneHash, codeHash, attempts, expiresAt}`, `ParentSession{token, parentUserId, activeSchoolId, expiresAt}`.

**Classes & Students**
- `SchoolClass` — `id, schoolId, name, gradeLevel, academicYear, homeroomTeacherId`.
- `Student` — `id, schoolId, fullName(enc), dateOfBirth, externalId, status`.
- `Enrollment` — `id, schoolId, studentId, classId` (M:N; supports multi-class tutoring). Unique(studentId, classId).
- `TeacherAssignment` — `id, schoolId, teacherUserId, classId`. Drives "teacher sees only their classes."
- `ParentStudentLink` — `id, schoolId, parentUserId, studentId, relationship, primaryContact`. Unique(parentUserId, studentId).

**Announcements**
- `Announcement` — `id, schoolId, senderId, scopeType, scopeId, language, body(enc), attachmentKey, requiresAck, scheduledFor, status, createdAt`.
- `AnnouncementRecipient` — `id, schoolId, announcementId, parentUserId, studentId, deliveryStatus, acknowledgedAt, messageId`. Index(announcementId), Index(schoolId, parentUserId).

**Homework**
- `HomeworkItem` — `id, schoolId, classId, teacherId, subject, description(enc), attachmentKey, dueDate, status, createdAt, updatedAt, reminderSentAt`.

**Attendance** *(hottest table)*
- `AttendanceRecord` — `id, schoolId, studentId, classId, date, status, markedByUserId, markedAt, parentResponse, parentRespondedAt`. **Unique(schoolId, studentId, classId, date)**. Index(schoolId, classId, date), Index(schoolId, studentId, date).

**Fees**
- `FeeItem` — `id, schoolId, name, amount(Money embedded), dueDate, scopeType, scopeId, createdAt`.
- `StudentFee` (ledger) — `id, schoolId, studentId, feeItemId, amountDue, amountPaid, balance, dueDate, lastPaymentDate, status`. Unique(studentId, feeItemId). Index(schoolId, status).
- `Payment` — `id, schoolId, studentId, feeItemId, amount(Money), paidAt, method, reference, recordedByUserId, createdAt`.

**Messaging**
- `Conversation` — `id, schoolId, teacherUserId, parentUserId, studentId, status, createdAt, lastMessageAt`. Unique(schoolId, teacherUserId, parentUserId, studentId).
- `Message` — `id, schoolId, conversationId(null), announcementId(null), direction, fromUserId, toUserId, body(enc), channel, status, whatsappMessageId, sentAt, deliveredAt, readAt, createdAt`. Index(conversationId, createdAt), Index(whatsappMessageId).
- `MessageReport` — `id, schoolId, messageId, reportedByUserId, reason, status, createdAt`.

**Compliance & infra**
- `ConsentRecord` — `id, schoolId, parentUserId, consentType, grantedAt, revokedAt` (PDPL §5.6).
- `OutboxEvent` — `id, schoolId, aggregateType, aggregateId, eventType, payload(jsonb), status, attempts, createdAt, publishedAt`. Index(status, createdAt).
- `AuditLog` — `id, schoolId, actorUserId, action, entityType, entityId, metadata(jsonb), ipAddress, createdAt`. **Append-only** (no update/delete mappers).
- *(Redis)* `IdempotencyRecord{key→(status, bodyHash, response)}` TTL 24h.

**Base class:** `BaseEntity{ id(UUIDv7), createdAt, updatedAt(@Version optional) }`; `TenantEntity extends BaseEntity { schoolId }` carrying the `@FilterDef/@Filter("tenantFilter")`.

---

## 3. Database Schema & Migrations

### 3.1 Liquibase changeset order
```
001-baseline.sql            ✅ pgcrypto
002-tenant.sql              schools, school_settings
003-identity.sql           users, refresh_tokens   (+ blind-index column phone_hash)
004-classes-students.sql   classes, students, enrollments, teacher_assignments, parent_student_links
005-announcements.sql      announcements, announcement_recipients
006-homework.sql           homework_items
007-attendance.sql         attendance_records
008-fees.sql               fee_items, student_fees, payments
009-messaging.sql          conversations, messages, message_reports
010-infra.sql              outbox_events, audit_logs, consent_records
```
Rules: forward-only; every changeset has `--rollback`; no `DROP` in numbered files; `school_id UUID NOT NULL` + FK on all tenant tables.

### 3.2 Key indexes
- `users (school_id, email)` unique; `users (phone_hash)` for parent lookup (blind index over encrypted phone).
- `attendance_records (school_id, student_id, class_id, date)` unique; covering indexes for roster & history reads.
- `outbox_events (status, created_at)` partial `WHERE status='PENDING'` for relay polling.
- `student_fees (school_id, status, due_date)` for reminder scheduler.
- `announcement_recipients (announcement_id) WHERE acknowledged_at IS NULL` for "not yet acknowledged".

### 3.3 Seed data
- `DEV-only` Liquibase context: one super-admin user, one demo school, demo classes/students/parents for local & integration smoke flows. Gated by `context:"dev"` so it never runs in prod.
- `NotificationTemplate` values live in code (enum), not seeded.

---

## 4. REST API Design

**Conventions:** base `/api/v1`, plural kebab paths, camelCase JSON, `Page<T>` envelope, `?page&size&sort`, explicit filter params, RFC 7807 errors (`application/problem+json` + `traceId` + `errors[]`), `Idempotency-Key` on writes, ISO-8601 UTC, UUIDv7 ids. Public/auth column: `PUB`=public, `S`=staff JWT, `P`=parent token, `SA`=super-admin.

### Auth
| M | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/auth/login` | PUB | staff email+pwd → access+refresh |
| POST | `/api/v1/auth/refresh` | PUB | rotate refresh |
| POST | `/api/v1/auth/logout` | S | revoke refresh |
| POST | `/api/v1/parents/auth/request-otp` | PUB | rate-limited; WhatsApp OTP |
| POST | `/api/v1/parents/auth/verify-otp` | PUB | → opaque token |
| POST | `/api/v1/parents/auth/select-school` | P | multi-school parents |

### Tenant (SA)
`POST/GET/GET{id} /api/v1/schools`, `GET/PUT /api/v1/schools/{id}/settings` (SCHOOL_ADMIN for own).

### Identity / Users (S)
`POST/GET/GET{id}/PATCH /api/v1/users`, `POST /api/v1/users/{id}/suspend`, `POST /api/v1/users:bulk-import` (CSV).

### Classes / Students / Links (S)
`/api/v1/classes` CRUD, `/api/v1/students` CRUD, `/api/v1/students:bulk-import`, `/api/v1/classes/{id}/enrollments`, `/api/v1/classes/{id}/teachers`, `/api/v1/parent-links`.

### Announcements
`POST/GET/GET{id} /api/v1/announcements` (S), `POST /{id}/recall` (S), `GET /{id}/recipients` (S, ack tracking), `POST /{id}/acknowledge` (P).

### Homework
`POST/GET/PATCH/DELETE /api/v1/homework` (S=teacher), `GET /api/v1/homework?childId=` (P feed).

### Attendance
`GET /api/v1/attendance/roster?classId=&date=` (S), `POST /api/v1/attendance:mark` (S, idempotent, triggers alert), `POST /api/v1/attendance:mark-all-present` (S), `GET /api/v1/attendance/history?studentId=&from=&to=` (S/P), `POST /api/v1/attendance/{id}/parent-response` (P).

### Fees
`/api/v1/fee-items` CRUD (S), `GET /api/v1/students/{id}/ledger` (S/P), `POST /api/v1/payments` (S, idempotent, audit), `POST /api/v1/payments:bulk-import` (S), `GET /api/v1/students/{id}/statement` (P → PDF / pre-signed URL).

### Messaging
`POST /api/v1/conversations` (S=teacher), `GET /api/v1/conversations` (S/P), `POST /api/v1/conversations/{id}/messages` (S/P), `GET /api/v1/conversations/{id}/messages` (S/P), `POST /api/v1/messages/{id}/report` (P).

### Reporting (S)
`GET /api/v1/reports/overview`, `/reports/attendance`, `/reports/fees`, `/reports/messages`, `GET /api/v1/audit-logs` (read-only).

### Integrations
`GET/POST /integrations/whatsapp/webhook` (PUB + `X-Hub-Signature-256` HMAC verify; not under `/api`).

### Error format (RFC 7807)
```json
{ "type":"https://schoolbridge/errors/validation","title":"Validation failed",
  "status":422,"detail":"...","instance":"/api/v1/...","traceId":"...",
  "errors":[{"field":"phone","message":"must be E.164"}] }
```

---

## 5. Security Implementation

### 5.1 Authentication flows
- **Staff:** `login` → verify Argon2id hash → issue RS256 JWT access (15m, claims `sub,schoolId,role`) + opaque refresh (30d, **hashed** in DB). `refresh` rotates and revokes prior. Keys: RSA keypair from secret manager / env (PEM), `kid` in header for rotation.
- **Parents:** `request-otp` → resolve phone (blind index) → 6-digit OTP (hashed) in Redis (TTL 5m, max attempts) → WhatsApp template send → `ticketId`. `verify-otp` → opaque 32-byte token in Redis (24h sliding) bound to `parentUserId + activeSchoolId`; revocation set in Redis.
- **TenantFilter** (after auth): extract `schoolId` from principal → `TenantContext.set()` → Hibernate filter param. No tenant ⇒ 401/403. Cleared in `finally`.

### 5.2 RBAC matrix (representative)
| Action | SUPER_ADMIN | SCHOOL_ADMIN | TEACHER | PARENT |
|---|:--:|:--:|:--:|:--:|
| Create school | ✅ | — | — | — |
| Manage users / import | — | ✅ | — | — |
| CRUD classes/students | — | ✅ | read own | — |
| Post homework / mark attendance | — | ✅ | ✅ own classes | — |
| Send announcement | — | ✅ | ✅ own classes | — |
| Define fees / record payment | — | ✅ | — | — |
| Start conversation | — | ✅ | ✅ own parents | reply only |
| View child data / ledger / ack | — | — | — | ✅ linked only |
| Reports / audit log | — | ✅ | — | — |

Enforced **server-side**: method security `@PreAuthorize` + a `PermissionEvaluator` for instance-level checks (`teacherTeaches(classId)`, `parentLinkedTo(studentId)`). Client checks advisory only (NFR-S4).

### 5.3 Other controls
- **Passwords:** Argon2id (`Spring Security Argon2PasswordEncoder`), policy ≥12 chars (NFR-S1).
- **CSRF:** disabled (stateless token APIs); compensated by no cookie auth.
- **CORS:** allowlist per environment (admin/teacher/parent origins).
- **Rate limiting:** Bucket4j + Redis — login 5 fails/15m/account (NFR-S5), OTP requests/phone, outbound send caps; `429` + `RateLimitException`.
- **Encryption at rest:** AES-256-GCM `AttributeConverter` for phone/name/message body (NFR-S3); blind-index SHA-256(HMAC) column for phone lookups. Key from KMS/env, versioned.
- **Webhook:** constant-time HMAC-SHA256 verify; reject unsigned.
- **No sensitive data in logs**; PII fields masked.

---

## 6. Business Logic & Service Layer

- One `*Service` interface + `*ServiceImpl` per aggregate; constructor injection only; `@Transactional` at service methods (write = default, read = `readOnly`).
- Transaction boundaries: a mark/announce/payment write **and** its `outbox_event` insert share one transaction; external publish happens after commit via the relay. Domain events via `@TransactionalEventListener(AFTER_COMMIT)`.
- **Business exceptions** map to the hierarchy: `NotFound/Validation/Authentication/Authorization/Conflict/RateLimit/Integration/TenantSecurity` → RFC 7807.
- **External adapters (ports):** `WhatsAppClient`, `SmsClient`, `EmailClient`, `StorageClient` — interface + Cloud impl + fake. `NotificationDispatcher` chooses channel (WhatsApp→SMS fallback after 2 failures/10m), wrapped in Resilience4j circuit breaker + retry.
- **Attendance alert pipeline (the SLA path):** mark saved → outbox row → relay → `schoolbridge.attendance` → consumer builds notification → `schoolbridge.notifications.out` → dispatcher → WhatsApp template. Target p95 < 60s mark→dispatch (NFR-P2). Metric: `attendance.alert.latency`.

---

## 7. Validation & Error Handling

- **Request validation:** Jakarta Bean Validation on DTO records (`@NotNull,@Email,@Size,@Pattern`), custom validators (`@E164Phone`, `@FutureOrPresentDate`, `@ValidScope`). `@Validated` on controllers; `MethodArgumentNotValidException` → 422 with `errors[]`.
- **Global handler:** single `@RestControllerAdvice` → `ProblemDetail`; messages resolved via `MessageSource` using request `Accept-Language` (ar/en). Unhandled → 500 generic + traceId, full stack logged.
- **Observability:** `RequestIdFilter` (MDC `requestId/traceId`), `TenantContext` adds `schoolId`, auth adds `userId`; `RequestLoggingFilter` (method/path/status/duration/size). Micrometer histograms per endpoint, queue depth, WhatsApp success rate, alert latency. `/actuator/health` liveness/readiness split. JSON logs in prod.

---

## 8. Testing Strategy

- **Unit (JUnit5 + Mockito + AssertJ):** every service public method ≥1 happy + 1 failure path. Target **80% lines** services, **100%** tenancy + security.
- **Slice:** `@WebMvcTest` (controllers, validation, error mapping), `@DataJpaTest` (repos + queries).
- **Integration:** `@SpringBootTest` + Testcontainers (Postgres + RabbitMQ + Redis via `@ServiceConnection`, base class already built) for full flows — esp. attendance-mark→alert end-to-end and outbox relay.
- **Tenant-isolation suite:** dedicated test per tenant repo: insert two schools, assert school B invisible to school A. **Mandatory, no exceptions.**
- **Security tests:** auth required, RBAC denials, webhook signature, rate-limit 429.
- **API contract:** OpenAPI as the contract for React/Flutter consumers; Spring Cloud Contract optional (defer — single team, schema-first via springdoc suffices for MVP).
- **Performance (hardening phase):** k6 scripts — attendance mark burst, 5k-recipient announcement (NFR-P4), parent read load.

---

## 9. Build, Configuration & Deployment

- **Build:** single-module Maven (split only if needed later). Spotless (google-java-format) + SpotBugs bound to `verify`; JaCoCo coverage; Error Prone deferred to JDK-21 toolchain.
- **Config profiles:** `local` (compose creds), `test` (Testcontainers), `prod` (env-var only, no secrets in files). Secrets: `DB_*`, `REDIS_*`, `RABBITMQ_*`, `JWT_PRIVATE_KEY`, `AES_KEY`, `WHATSAPP_TOKEN`, `WHATSAPP_APP_SECRET`, `OTEL_EXPORTER_OTLP_ENDPOINT`.
- **Docker:** multi-stage distroless Java 21 image (built); `docker-compose.yml` = Postgres16 + RabbitMQ + Redis7 + MinIO (built).
- **CI/CD sketch:** PR → `mvn verify` (compile, spotless:check, spotbugs, unit+slice+IT w/ Testcontainers, JaCoCo gate) → build & scan image → push to registry → deploy `prod` (DB migrate via Liquibase on boot or a pre-deploy job) → smoke `/actuator/health`. Quality gate blocks merge on coverage/static-analysis failure.

---

## 10. Development Roadmap & Milestones

| Phase | Module(s) | Demo deliverable | Parallelizable |
|---|---|---|---|
| ✅ M1 | Project skeleton | boots, health UP | — |
| M2 | Common infra (tenancy, JWT, RFC7807, idempotency, i18n, crypto, outbox base) | filters + advice unit-tested | crypto + i18n parallel |
| M3 | Tenant | SA creates school + settings | — (needs M2) |
| M4 | Identity | staff login/refresh + parent OTP | — |
| M5 | Classes/Students/Links | CSV import, enrollments | after M4 |
| M6 | Announcements | ack tracking + outbox event | after M5 |
| M7 | WhatsApp integration | template send + webhook | **parallel with M6** (own boundary) |
| M8 | **Attendance** | mark→alert ≤5min end-to-end | needs M5+M7 |
| M9–M11 | Homework, Fees, Messaging | feeds, ledger+reminders, quiet hours | Homework ∥ Fees |
| M12–M13 | Repo<br/>rting, Audit+admin | dashboards, immutable log | parallel |
| M14 | Hardening | security review, k6, runbook | — |

**Critical path:** M2 → M4 → M5 → M7 → **M8** (the SRS's highest-value SLA). Reporting/Homework/Fees can fan out once M5 lands.

---

## Phase 2 — `/springboot-patterns` scaffolding (pending your go-ahead)

When approved, I'll run `/springboot-patterns` to generate scaffolding **aligned to this package design** (base entity/DTO, repository interfaces, controller stubs, SecurityConfig skeleton, global exception handler, validation). Patterns it likely won't cover — **Outbox relay, Hibernate `@Filter` tenancy, AES-GCM converter, parent-OTP, WhatsApp ports** — I'll flag and implement manually in M2/M4/M7. I will **not** overwrite the existing Module 1 files.

### Risks
- **HIGH** — tenancy correctness (Hibernate `@Filter` must be active on *every* read incl. lazy loads & native queries). Mitigation: 100% isolation test coverage + an `ArchUnit`/test guard that fails if a `TenantEntity` lacks `@Filter`.
- **HIGH** — attendance alert SLA under burst. Mitigation: outbox + dedicated consumer + latency metric + load test.
- **MED** — encryption + searchability of phone (can't query encrypted column). Mitigation: blind-index hash column.
- **MED** — WhatsApp template approval lead time & rate tiers. Mitigation: enum registry + fake client; SMS fallback.
- **MED** — idempotency cache correctness across retries. Mitigation: key+request-hash match, store response.

**Complexity:** HIGH overall (multi-tenant SaaS + external integration + SLA). MVP critical path (M2–M8) is the bulk of effort.
