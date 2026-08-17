---
name: spring-boot-patterns
description: Spring Boot best practices and patterns for SchoolBridge. Covers configuration, caching, outbox events, scheduling, and validation patterns.
metadata:
  version: "2.0.0"
  domain: backend
  triggers: Spring Boot patterns, Spring configuration, caching, scheduling, outbox, validation
  role: specialist
  scope: patterns
  output-format: code
---

# Spring Boot Patterns Skill (SchoolBridge)

## Configuration Pattern

```java
@Configuration
@ConfigurationProperties(prefix = "schoolbridge.whatsapp")
public class WhatsAppProperties {
  private String appSecret;
  private String accessToken;
  private String phoneNumberId;
  // getters/setters — this is one of the few places a mutable bean genuinely needs them
}
// application.yml: schoolbridge.whatsapp.app-secret=${WHATSAPP_APP_SECRET}
```

## Caching Pattern

Redis is available (`spring-boot-starter-data-redis`) — this project is not Caffeine-only. The
real example is `EffectivePermissionService`, a role-keyed cache of the role→permission mapping:

```java
@Service
public class EffectivePermissionService {

  @Cacheable(value = "rolePermissions", key = "#role")
  public Set<Permission> permissionsFor(UserRole role) {
    return rolePermissionRepository.findByRole(role).stream()
        .map(RolePermission::getPermission)
        .collect(Collectors.toSet());
  }

  @CacheEvict(value = "rolePermissions", key = "#role")
  public void grant(UserRole role, Permission permission) {
    // ...
  }
}
```
Any cache key over tenant-scoped data **must include the school id** — an unscoped key is a
cross-tenant leak, not just a staleness bug.

## Outbox Pattern (this project's cross-module async mechanism)

There is no in-process `ApplicationEventPublisher`/`@EventListener` convention for cross-module
communication here — that's used only for genuinely in-module listeners (e.g.
`PermissionCatalogReconciler`, `SchoolServiceImpl`). Cross-module side effects go through an outbox
row written in the same transaction as the domain change:

```java
// In the producing service, same transaction as the domain write
Map<String, Object> payload = new HashMap<>(); // never Map.of(...) — nullable fields NPE
payload.put("homeworkId", saved.getId());
payload.put("classId", saved.getClassId());
outboxEventRecorder.record("homework.published", payload);
```

`OutboxRelay` (`common/outbox`) is a `@Scheduled` poller, disabled by default
(`@ConditionalOnProperty(name = "schoolbridge.outbox.relay.enabled")`):

```java
@Component
@ConditionalOnProperty(name = "schoolbridge.outbox.relay.enabled", havingValue = "true")
public class OutboxRelay {

  @Scheduled(fixedDelayString = "${schoolbridge.outbox.relay.poll-interval:5s}")
  @Transactional
  public void publishPending() {
    List<OutboxEvent> batch = repository.claimDue(Instant.now(), PageRequest.of(0, batchSize));
    // claimDue uses FOR UPDATE SKIP LOCKED — multiple instances split the work, never double-publish
    for (OutboxEvent event : batch) {
      try {
        publisher.publish(event);
      } catch (Exception e) {
        event.markFailed(); // exponential backoff; DEAD only after MAX_ATTEMPTS — a broker blip
      }                      // is a retry, not a dropped notification
    }
  }
}
```

## Scheduling Pattern

Real sweepers in this codebase: `AttendanceSweeper`, `AttachmentSweeper`, `HomeworkReminderSweeper`,
`AnnouncementScheduleSweeper`, `AnnouncementDeferralSweeper`, `OutboxRelay`.

```java
@Component
public class AttachmentSweeper {

  private final AttachmentRepository repository;

  public AttachmentSweeper(AttachmentRepository repository) {
    this.repository = repository;
  }

  @Scheduled(cron = "${schoolbridge.attachments.sweeper.cron:0 0 3 * * *}")
  @Transactional
  public void deleteAbandonedUploads() {
    // PENDING rows older than the abandonment threshold — the API is never told a client's PUT
    // landed, so this is how orphaned presign requests get cleaned up
    repository.deleteAbandonedBefore(Instant.now().minus(24, ChronoUnit.HOURS));
  }
}
```
A sweeper that runs on more than one instance must be safe to double-run — dedup via a DB-visible
flag/timestamp set at the *start* of the work (see `HomeworkReminderSweeper`'s `reminderSentAt`
convention), not an in-memory lock.

## Validation Pattern

```java
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PhoneNumberValidator.class)
public @interface ValidPhoneNumber {
    String message() default "{validation.user.phone.invalid}";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class PhoneNumberValidator implements ConstraintValidator<ValidPhoneNumber, String> {
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[1-9]\\d{7,14}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || PHONE_PATTERN.matcher(value).matches();
    }
}
```
Every validation message key needs an entry in **both** `messages_ar.properties` and
`messages_en.properties`.

## Transaction-Boundary Pattern (external calls after commit)

```java
// Execute an external call AFTER the DB transaction commits, not during it
@Transactional
public void notifyParent(UUID recipientId, String message) {
    // ... update DB state

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Only reach WhatsApp/FCM/SMS after the DB transaction has actually committed
                notificationClient.send(recipientId, message);
            }
        });
}
```
In most of this codebase the outbox pattern above achieves the same effect more durably (a crash
between commit and the external call just leaves the outbox row `PENDING` for the relay to pick up)
— prefer the outbox unless there's a specific reason the call must happen inline.

## Profile-Based Configuration

```
src/main/resources/
├── application.yml         # Shared defaults
├── application-local.yml   # Local dev (gitignored)
└── application-prod.yml    # Production overrides
```

```bash
# Run with local profile
java -jar target/api-0.1.0-SNAPSHOT.jar --spring.profiles.active=local

# Maven test (uses the "test" profile automatically via AbstractIntegrationTest)
mvnw.cmd test
```
