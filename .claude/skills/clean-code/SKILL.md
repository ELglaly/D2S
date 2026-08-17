---
name: clean-code
description: DRY, KISS, YAGNI, and naming principles applied to SchoolBridge Java code. Use during code review or refactoring.
metadata:
  version: "2.0.0"
  domain: code-quality
  triggers: clean code, code quality, refactor, naming, DRY, KISS, YAGNI
  role: reviewer
  scope: code-quality
  output-format: checklist
---

# Clean Code Skill (SchoolBridge)

## Naming

- [ ] Classes are nouns: `HomeworkService`, `AttendanceController`, `NotificationPreference`
- [ ] Methods are verbs: `findById`, `publish`, `acknowledge`
- [ ] Booleans use is/has/can: `isActive`, `hasStock`, `requiresAck`
- [ ] No abbreviations: `hwSvc` → `homeworkService`
- [ ] Test names describe behavior: `publish_shouldReturn403_whenCallerIsNotTheOwningTeacher`
- [ ] Avoid the bare word "class" in code (`SchoolClass` is the entity name — reserved-word
      collision and ambiguity with `UserRole`/enum classes)

## Functions / Methods

- [ ] < 50 lines per method
- [ ] Single responsibility: does one thing, with a name that proves it
- [ ] No more than 3 parameters (use a request record for more)
- [ ] No boolean parameters that control behavior (split into two methods)
- [ ] Fail fast: validate inputs at the top, return early

## Classes / Files

- [ ] < 400 lines per class (warning at 400, hard limit at 800)
- [ ] Single responsibility: one reason to change
- [ ] Service classes should not exceed ~10 public methods (consider splitting, e.g.
      `HomeworkService` + `HomeworkReminderService`)

## DRY (Don't Repeat Yourself)

- [ ] No duplicate recipient-materialization logic — `AnnouncementServiceImpl.materializeRecipients`
      is the pattern other fan-out code should follow, not reinvent
- [ ] No duplicate validation logic (use a shared DTO or custom validator)
- [ ] No duplicate `toResponse()` mapping logic — extract a single private method per entity, not
      one per caller
- [ ] `AbstractIntegrationTest` helpers used consistently across test classes

## KISS (Keep It Simple)

- [ ] No over-engineering for hypothetical future features
- [ ] No abstract factory when a simple constructor or `@Bean` method works
- [ ] No design pattern applied without a concrete benefit

## YAGNI (You Aren't Gonna Need It)

- [ ] No unused service methods
- [ ] No unused DTO fields
- [ ] No commented-out code blocks
- [ ] No "TODO: maybe add later" code paths

## SchoolBridge-Specific Clean Code

```java
// BAD: scattered inline mapping
return new HomeworkResponse(h.getId(), h.getTitle(), h.getDueDate(), h.getStatus(), h.getSchoolId());
// ^ repeated in 5 controller methods

// GOOD: single private toResponse() in the service
private HomeworkResponse toResponse(HomeworkItem h) {
    return new HomeworkResponse(h.getId(), h.getTitle(), h.getDueDate(), h.getStatus(), h.getSchoolId());
}

// BAD: magic status string
if (homework.getStatus().equals("PUBLISHED")) { ... }

// GOOD: enum constant
if (homework.getStatus() == HomeworkStatus.PUBLISHED) { ... }

// BAD: outbox payload that NPEs the moment a field is null
outboxEventRecorder.record("homework.published", Map.of(
    "homeworkId", h.getId(), "attachmentKey", h.getAttachmentKey())); // NPEs if attachmentKey is null

// GOOD: HashMap, tolerates nullable fields
Map<String, Object> payload = new HashMap<>();
payload.put("homeworkId", h.getId());
payload.put("attachmentKey", h.getAttachmentKey());
outboxEventRecorder.record("homework.published", payload);

// BAD: colon-verb action path
@PostMapping("/{id}:publish")

// GOOD: slash-style
@PostMapping("/{id}/publish")
```
