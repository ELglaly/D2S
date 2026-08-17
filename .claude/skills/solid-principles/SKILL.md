---
name: solid-principles
description: S.O.L.I.D. principles with Spring Boot examples from SchoolBridge. Use during architecture review or when designing new features.
metadata:
  version: "2.0.0"
  domain: architecture
  triggers: SOLID, single responsibility, open closed, interface segregation, dependency inversion
  role: reviewer
  scope: design
  output-format: checklist
---

# SOLID Principles Skill (SchoolBridge)

## S — Single Responsibility Principle

Each class has one reason to change.

**Checklist:**
- [ ] `HomeworkService` does not handle reminder scheduling (→ `HomeworkReminderService`)
- [ ] `AnnouncementService` does not handle recipient dispatch (→ the outbox + `integrations` consumer)
- [ ] `AuthService`/`AuthController` does not handle general user profile updates
- [ ] Controllers only coordinate HTTP; no business logic
- [ ] Sweepers (`AttendanceSweeper`, `AttachmentSweeper`, `HomeworkReminderSweeper`,
      `AnnouncementScheduleSweeper`) each own exactly one scheduled concern

**Warning sign**: Service class > 400 lines with methods from multiple domains.

## O — Open/Closed Principle

Open for extension, closed for modification.

**SchoolBridge examples:**
- New notification channel: implement a client the `NotificationDispatcher` can walk, don't modify
  the dispatcher's core loop for a one-off channel
- New assistant tool: add a class under `assistant/tools/<domain>`, don't grow the existing tool
  registry's switch statement
- New discount/offer-style variation: add an enum value + handle it where the enum is switched on,
  don't scatter new `if/else` branches across services

## L — Liskov Substitution Principle

Subtypes must be substitutable for their base types.

**Checklist:**
- [ ] Any class implementing a repository interface works the same way from the service's
      perspective
- [ ] Custom exceptions extend `RuntimeException` and are handled uniformly by the global exception
      handler via `ErrorType`
- [ ] Every `TenantEntity` subclass genuinely honors the tenant-scoping contract — a subclass that
      needs to bypass it (rare, e.g. a platform-admin cross-tenant read) should not silently extend
      `TenantEntity` and then work around the filter

## I — Interface Segregation Principle

Clients should not depend on methods they don't use.

**Checklist:**
- [ ] No "fat" repository interfaces with 30+ methods (split if needed)
- [ ] Controllers inject the narrowest service interface needed
- [ ] Avoid a `GenericService<T>` with every CRUD method when only a subset is used

## D — Dependency Inversion Principle

Depend on abstractions, not concretions.

**Checklist:**
- [ ] Services inject repository interfaces (`HomeworkItemRepository`), not JPA implementations
- [ ] Controllers inject service **interfaces** via constructor injection
- [ ] `@RequiredArgsConstructor` + `final` fields = constructor injection (no `@Autowired` on fields)
- [ ] Configuration uses `@Bean` factory methods, not direct `new MyService()` calls

**SchoolBridge constructor injection pattern:**
```java
@Service
@RequiredArgsConstructor
public class HomeworkServiceImpl implements HomeworkService {
    private final HomeworkItemRepository homeworkItemRepository;   // depends on interface
    private final HomeworkRecipientRepository recipientRepository;
    private final OutboxEventRecorder outboxEventRecorder;         // depends on shared infra
}
```
