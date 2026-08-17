---
name: performance-smell-detection
description: Code-level performance smells for SchoolBridge Spring Boot. Covers N+1 queries, missing pagination, eager fetching, stream boxing, regex in loops, and missing indexes.
metadata:
  version: "2.0.0"
  domain: performance
  triggers: performance, N+1, slow query, pagination, lazy loading, optimization
  role: reviewer
  scope: performance
  output-format: checklist
---

# Performance Smell Detection Skill (SchoolBridge)

## Query Performance

### N+1 Query Detection
```java
// SMELL: N+1 — one query for homework items, N queries for each item's recipients
List<HomeworkItem> items = homeworkRepository.findAll();
items.forEach(h -> h.getRecipients().size()); // triggers N lazy loads

// FIX: join fetch in repository
@Query("SELECT h FROM HomeworkItem h JOIN FETCH h.recipients WHERE h.schoolId = :schoolId")
List<HomeworkItem> findBySchoolIdWithRecipients(@Param("schoolId") UUID schoolId);
```
- [ ] No `getX().size()` or `getX().stream()` on lazy collections in loops
- [ ] `JOIN FETCH` or `@EntityGraph` used when loading collections in bulk
- [ ] `@BatchSize` used on collections when full fetch is not needed

### Fan-out Writes (not just reads)
- [ ] Recipient/notification fan-out uses `repository.saveAll(list)`, never a per-row loop — a
      school class of 30-50 students can mean 50-100 recipient rows per publish; a class of 5000
      (school-wide announcement) makes an unbatched loop catastrophic
      (`AnnouncementServiceImpl.materializeRecipients` is the reference implementation)

### Missing Pagination
- [ ] All list endpoints return `Page<T>` (not `List<T>`) with a `Pageable` parameter
- [ ] Default page size is bounded (e.g., max 100 items per page)
- [ ] The parent feed query is indexed for its actual access pattern
      (`school_id, parent_user_id, created_at desc` style composite index), not scanned per request

### Eager Fetching
- [ ] No `FetchType.EAGER` on `@ManyToOne` or `@OneToMany` associations
- [ ] All new associations added as `FetchType.LAZY` (the JPA default for `@OneToMany`)

### Missing Database Indexes
Check via `EXPLAIN (ANALYZE, BUFFERS)` for any new or slow query; likely candidates by module:
- `homework_recipient.parent_user_id`, `homework_item.school_id` — parent feed / teacher list
- `attendance.student_id`, `attendance.class_id`, `attendance.date` — roster and history queries
- `announcement_recipient.parent_user_id` — parent feed
- `attachment.status` (partial index `WHERE status = 'PENDING'`) — the abandonment sweeper's scan
- `device_tokens.school_id, user_id` where `active = TRUE` (partial index) — push fan-out lookup,
  see `008-device-tokens.sql` for the pattern
- Every `school_id` FK column on a tenant-scoped table — the Hibernate `@Filter` predicate hits it
  on every single query

## Java Performance

### Stream Boxing
```java
// SMELL: autoboxing Integer → int in stream
int total = items.stream().mapToInt(Integer::intValue).sum(); // boxing
// FIX:
int total = items.stream().mapToInt(HomeworkRecipient::getAttemptCount).sum(); // no boxing
```

### Regex Compilation in Loops
```java
// SMELL: pattern compiled on every call
public boolean isValid(String input) {
    return input.matches("[A-Za-z]+"); // compiles regex each time
}
// FIX:
private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z]+");
public boolean isValid(String input) {
    return NAME_PATTERN.matcher(input).matches();
}
```

### String Concatenation in Loops
```java
// SMELL
String result = "";
for (String s : list) result += s; // O(n²)
// FIX
StringBuilder sb = new StringBuilder();
for (String s : list) sb.append(s);
```

## Caching Opportunities

This project has **Redis** available (`spring-boot-starter-data-redis`) — not Caffeine-only. Use it
for anything that needs to be consistent across instances (rate limiting already does).

- [ ] `SubjectService.getAll()` / `CategoryService`-style rarely-changing catalog reads — good cache candidates
- [ ] Provider/teacher-facing profile data that changes infrequently
- [ ] `AvailabilitySlot`/roster-style reads that change on write — cache with a short TTL, or evict
      explicitly on the write path rather than relying on TTL alone

## Cache Checklist
- [ ] `@Cacheable` used on frequently read, rarely written methods
- [ ] `@CacheEvict` used on write methods that invalidate cached data
- [ ] Cache keys include `school_id` (or the equivalent tenant discriminator) — a cache key that
      doesn't include the tenant is a cross-tenant data leak waiting to happen, not just a staleness bug
- [ ] TTL is set appropriately (not infinite for mutable data)
