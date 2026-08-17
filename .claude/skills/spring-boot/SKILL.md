---
name: spring-boot
description: Spring Boot 3.4.x development - REST APIs, JPA, tenant isolation, outbox events, Testing. Use for building SchoolBridge backend features.
metadata:
  version: "3.0.0"
  domain: backend
  triggers: Spring Boot, Spring Framework, Spring Security, Spring Data JPA, Java REST API
  role: specialist
  scope: implementation
  output-format: code
---

# Spring Boot Skill (SchoolBridge)

Spring Boot 3.4.5 development, package-by-feature into 14 modules, tenant isolation as the core
architectural constraint.
Tuned for the **SchoolBridge** project: `com.schoolbridge.api`, Java 21.

## Core Workflow

1. **Analyze** — Identify the owning module, confirm whether the change is cross-module
2. **Design** — If cross-module: decide direct service call (sync) vs. outbox event (async)
3. **Implement** — Constructor injection, Java records for DTOs, layered
   (Controller → Service/`*Impl` → Repository)
4. **Secure** — `@RequirePermission` + row-ownership `@PreAuthorize`; a public endpoint is a
   deliberate `SecurityConfig` addition
5. **Test** — `AbstractIntegrationTest` + REST Assured; a cross-tenant isolation test for any new
   `TenantEntity` repository
6. **Verify** — `mvnw.cmd -DskipTests compile`, `mvnw.cmd spotless:apply`, affected tests,
   `TenantEntityArchUnitTest` if a tenant entity was touched

## Project Layout

```
src/main/java/com/schoolbridge/api/
├── common/            # TenantEntity, ApiResponse envelope, outbox, crypto, i18n, security/authz
├── config/            # ApplicationConfig, OpenApiConfig
├── tenant/            # School onboarding
├── identity/          # Users, JWT auth, OTP, device tokens
├── classes/           # SchoolClass, Student, Enrollment, ParentStudentLink
├── subjects/          # Subject catalog
├── grades/            # Grade records
├── announcements/     # Announcements + recipients
├── attendance/        # Attendance + absence alerts
├── homework/          # Homework items + recipients + reminders
├── attachments/       # Presigned upload/download pipeline
├── notifications/     # Per-user notification preferences
├── integrations/      # WhatsApp/FCM/SMS adapters, RabbitMQ consumers
└── assistant/          # AI assistant (ships dark)
```

No `internal`/`api`/`web` sub-split — a module's entity, repository, service, and controller live at
its root; `dto/` is the one standard sub-package.

## Quick Start Templates

### Entity — explicit, no Lombok
**Lombok is a declared dependency but is not used anywhere in `src/main/java`.** Entities are
hand-written: a protected no-arg constructor for JPA, a public constructor taking every field, plain
`get*()` methods, and rich domain methods instead of setters. `HomeworkItem` is the canonical
example:

```java
@Entity
@Table(name = "homework_items")
public class HomeworkItem extends TenantEntity {

  @Column(name = "class_id", nullable = false, updatable = false)
  private UUID classId;

  @Column(nullable = false, length = 200)
  private String subject;

  @Column(name = "due_date", nullable = false)
  private LocalDate dueDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private HomeworkStatus status;

  protected HomeworkItem() {} // JPA

  public HomeworkItem(UUID schoolId, UUID classId, String subject, LocalDate dueDate) {
    super(schoolId);
    this.classId = classId;
    this.subject = subject;
    this.dueDate = dueDate;
    this.status = HomeworkStatus.DRAFT;
  }

  public void publish() {
    this.status = HomeworkStatus.PUBLISHED;
  }

  public UUID getClassId() {
    return classId;
  }

  public HomeworkStatus getStatus() {
    return status;
  }
}
```

### Repository — mandatory `findById` override for a `TenantEntity`
```java
public interface HomeworkItemRepository extends JpaRepository<HomeworkItem, UUID> {

  @Override
  @Query("select h from HomeworkItem h where h.id = :id")
  Optional<HomeworkItem> findById(@Param("id") UUID id);
}
```
This is not optional. `EntityManager.find()` (what the default `findById` calls) bypasses the
Hibernate `@Filter` entirely — see `docs/COMMON_MISTAKES.md` #1.

### Request DTO (Java record)
```java
public record CreateHomeworkRequest(
    @NotNull UUID classId,
    @NotBlank String subject,
    @NotNull LocalDate dueDate,
    boolean publish
) {}
```

### Response DTO (Java record, in `<module>/dto/`)
```java
public record HomeworkResponse(
    UUID id,
    UUID classId,
    String subject,
    LocalDate dueDate,
    HomeworkStatus status
) {}
```

### Service
```java
public interface HomeworkService {
  HomeworkResponse create(UUID schoolId, UUID actorId, CreateHomeworkRequest request);
}

@Service
public class HomeworkServiceImpl implements HomeworkService {

  private final HomeworkItemRepository repository;
  private final OutboxEventRecorder outboxEventRecorder;

  public HomeworkServiceImpl(
      HomeworkItemRepository repository, OutboxEventRecorder outboxEventRecorder) {
    this.repository = repository;
    this.outboxEventRecorder = outboxEventRecorder;
  }

  @Override
  @Transactional
  public HomeworkResponse create(UUID schoolId, UUID actorId, CreateHomeworkRequest request) {
    HomeworkItem item = new HomeworkItem(schoolId, request.classId(), request.subject(), request.dueDate());
    if (request.publish()) {
      item.publish();
    }
    HomeworkItem saved = repository.save(item);

    if (saved.getStatus() == HomeworkStatus.PUBLISHED) {
      Map<String, Object> payload = new HashMap<>(); // never Map.of(...) — nullable fields
      payload.put("homeworkId", saved.getId());
      payload.put("classId", saved.getClassId());
      outboxEventRecorder.record("homework.published", payload);
    }
    return toResponse(saved);
  }

  private HomeworkResponse toResponse(HomeworkItem h) {
    return new HomeworkResponse(h.getId(), h.getClassId(), h.getSubject(), h.getDueDate(), h.getStatus());
  }
}
```

### REST Controller
```java
@RestController
@RequestMapping(ApiConstants.API_V1 + "/homework")
@Tag(name = "Homework")
public class HomeworkController {

  private final HomeworkService service;

  public HomeworkController(HomeworkService service) {
    this.service = service;
  }

  @PostMapping
  @RequirePermission(Permission.HOMEWORK_CREATE)
  @PreAuthorize("hasRole('SCHOOL_ADMIN') or (hasRole('TEACHER') and @perms.teacherTeaches(#request.classId()))")
  public ResponseEntity<HomeworkResponse> create(
      @Valid @RequestBody CreateHomeworkRequest request, Authentication authentication) {
    UUID schoolId = TenantContext.require();
    UUID actorId = requireStaff(authentication).userId();
    HomeworkResponse created = service.create(schoolId, actorId, request);
    return ResponseEntity.created(URI.create(ApiConstants.API_V1 + "/homework/" + created.id()))
        .body(created);
  }
}
```
Note the return type: `ResponseEntity<HomeworkResponse>`, **not**
`ResponseEntity<ApiResponse<HomeworkResponse>>` — `ApiResponseBodyAdvice` wraps the body into the
envelope automatically. Hand-wrapping double-wraps it.

### Cross-Module / Async Side Effect — Outbox, not in-process events
There is no cross-module `ApplicationEventPublisher`/`@EventListener` convention here (that pattern
is used only for genuinely in-module listeners, e.g. `PermissionCatalogReconciler`). A side effect
that needs to reach another module goes through the **outbox**:

```java
// Same transaction as the domain write
Map<String, Object> payload = new HashMap<>();
payload.put("homeworkId", saved.getId());
outboxEventRecorder.record("homework.published", payload);
```

`OutboxRelay` (`common/outbox`) polls due rows on a fixed delay, claims them with
`FOR UPDATE SKIP LOCKED` (so more than one instance splits the work instead of double-publishing),
and publishes to RabbitMQ; a failure schedules exponential backoff and only parks the row `DEAD`
after `MAX_ATTEMPTS` — a broker blip does not silently drop the event.

## Reference Guide

| Topic | Reference | When to Load |
|---|---|---|
| Domain model | `.claude/rules/java/schoolbridge-domain-model.md` | Entities, enums, module ownership |
| Module catalog | `.claude/rules/java/schoolbridge-modules.md` | 14-module structure, dependency direction |
| API map | `.claude/rules/java/schoolbridge-api-map.md` | REST surface |
| Common mistakes | `docs/COMMON_MISTAKES.md` | Before touching tenancy, outbox, webhooks, storage |
| Architecture | `docs/ARCHITECTURE.md` | System-level shape, request flow |

## Constraints

### MUST DO
- Constructor injection (explicit constructor — no Lombok, no field `@Autowired`)
- `@Valid` on every `@RequestBody`
- `@Transactional` for multi-step writes; `@Transactional(readOnly = true)` for lazy-traversing reads
- Return the domain type / `ResponseEntity<T>` — let `ApiResponseBodyAdvice` wrap it
- i18n keys present in both `messages_ar.properties` and `messages_en.properties`
- `TenantEntity` repositories override `findById` with an explicit `@Query`
- Outbox payloads: `HashMap`, never `Map.of(...)`
- Slash-style action paths, never `:verb`

### MUST NOT DO
- Field injection (`@Autowired` on fields)
- Skip `@Valid` on a request body
- Store secrets in `application.yml` — use env vars / `application-local.yml` (gitignored)
- Use any package other than `com.schoolbridge.api.*` for new classes
- Return raw entities from controllers (use DTO records)
- Use `LocalDateTime` for cross-zone timestamps (use `Instant`)
- Use `double`/`float` for money (use `BigDecimal`/`NUMERIC`)
- Introduce Lombok annotations into new entity/service code — it's unused by convention here

## Authoritative Enum Values (do not invent — grep the real file if unsure)

- `UserRole`: `SUPER_ADMIN, SCHOOL_ADMIN, TEACHER, PARENT`
- `HomeworkStatus`: `DRAFT, PUBLISHED, ARCHIVED`
- `AttendanceStatus`: `PRESENT, ABSENT, LATE, EXCUSED`
- `AttachmentStatus`: `PENDING, UPLOADED, CLEAN, REJECTED, INFECTED`
- `AnnouncementStatus`: `DRAFT, SCHEDULED, SENDING, SENT, RECALLED`
- Full list: `.claude/rules/java/schoolbridge-domain-model.md`

## Common Annotations

| Annotation | Purpose |
|---|---|
| `@RestController` | REST controller |
| `@Service` | Business logic component |
| `@Repository`/`JpaRepository` | Data access |
| `@Transactional` | Transaction management |
| `@Valid` | Trigger Bean Validation |
| `@RequirePermission` | Fine-grained, DB-backed permission gate (AOP) |
| `@PreAuthorize` | Row-ownership / narrower checks alongside `@RequirePermission` |
| `@Scheduled` | Sweepers (`AttendanceSweeper`, `HomeworkReminderSweeper`, `OutboxRelay`, ...) |

## Knowledge Base

Spring Boot 3.4.5, Java 21, Spring Security 6.x, JWT, PostgreSQL, Liquibase, RabbitMQ, Redis,
Resilience4j, Spring AI (Anthropic/OpenAI-compatible), pgvector, AWS SDK v2 (S3-compatible storage),
Firebase Admin (FCM), Testcontainers, JUnit 5, REST Assured, AssertJ, ArchUnit, Maven.
