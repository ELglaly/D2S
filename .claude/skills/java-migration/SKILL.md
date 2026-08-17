---
name: java-migration
description: Java modern-features guide. Focuses on features used in SchoolBridge: records, sealed classes, pattern matching, text blocks, switch expressions, virtual threads.
metadata:
  version: "2.0.0"
  domain: java
  triggers: Java upgrade, Java migration, Java 21, modern Java, virtual threads
  role: guide
  scope: migration
  output-format: guide
---

# Java Migration Skill

SchoolBridge targets **Java 21** (`pom.xml` `<java.version>`). This skill documents what modern
features are available and how to use them.

## Language Features Available in SchoolBridge (Java 21)

### Records (Java 16+)
```java
// Use for immutable DTOs
public record CreateHomeworkRequest(
    @NotBlank String title,
    @NotNull LocalDate dueDate,
    UUID classId
) {}

// Compact constructor for validation
public record MarkAttendanceRequest(List<AttendanceEntry> entries) {
    public MarkAttendanceRequest {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.isEmpty()) throw new IllegalArgumentException("entries must not be empty");
    }
}
```

### Pattern Matching instanceof (Java 16+)
```java
// Old
if (exception instanceof ResourceNotFoundException) {
    ResourceNotFoundException ex = (ResourceNotFoundException) exception;
    return ex.getErrorType();
}

// New
if (exception instanceof ResourceNotFoundException ex) {
    return ex.getErrorType();
}
```

### Text Blocks (Java 15+)
```java
// Multi-line SQL in tests or logging
String query = """
    SELECT h.*, c.name AS class_name
    FROM homework_item h
    JOIN school_class c ON h.class_id = c.id
    WHERE h.status = 'PUBLISHED'
    """;
```
Note: text blocks with a literal `\n` fed into `.formatted(...)`/`String.format` trip SpotBugs'
`VA_FORMAT_STRING_USES_NEWLINE` — use `.replace("{x}", v)` for templated multi-line strings instead
(`docs/COMMON_MISTAKES.md` #10).

### Switch Expressions (Java 14+)
```java
// Old switch statement
String message;
switch (status) {
    case DRAFT: message = "Not published yet"; break;
    case PUBLISHED: message = "Published"; break;
    default: message = "Archived";
}

// New switch expression
String message = switch (status) {
    case DRAFT -> "Not published yet";
    case PUBLISHED -> "Published";
    default -> "Archived";
};
```

### Sealed Classes (Java 17+)
```java
// For type-safe event hierarchies
public sealed interface AssistantToolResult
    permits ToolSuccess, ToolConfirmationRequired, ToolFailure {}

public record ToolSuccess(Object data) implements AssistantToolResult {}
public record ToolConfirmationRequired(String token) implements AssistantToolResult {}
public record ToolFailure(String reason) implements AssistantToolResult {}
```

### Virtual Threads (Java 21+) — Spring Boot 3.2+ supported
```properties
# In application.properties/yml
spring.threads.virtual.enabled=true
```
Useful for I/O-bound operations (WhatsApp API calls, S3-compatible storage calls, the assistant's
LLM calls). No code changes required — Spring handles it automatically. Verify this isn't already
set intentionally one way or the other before flipping it.

### SequencedCollections (Java 21)
```java
// New methods on List, Set, Map
list.getFirst();  // replaces list.get(0)
list.getLast();   // replaces list.get(list.size() - 1)
list.reversed();  // returns reversed view
```

## What NOT to Use (Anti-patterns with Modern Java)

- Do NOT use Records for JPA entities (JPA requires mutable objects with a no-arg constructor —
  entities in this project are Lombok-annotated classes, not records)
- Do NOT use Records for classes that need inheritance (e.g. anything extending `TenantEntity`)
- Do NOT use `var` where it harms readability (`var x = doSomethingComplex()`)
- Prefer explicit types in method signatures; use `var` only in local variable declarations
