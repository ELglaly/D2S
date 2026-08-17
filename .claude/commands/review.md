---
description: Run a comprehensive code review on recently modified SchoolBridge Java files.
argument-hint: [file-path or leave blank for git diff]
---

Perform a comprehensive code review on the SchoolBridge codebase.

$ARGUMENTS

## Review Scope

If a file path was provided, review that file.
If no argument, run: `git diff HEAD --name-only` to find recently changed `.java` files and review those.

## Review Checklist

Apply the full `java-code-review` skill. For each file, check:

### CRITICAL (block merge)
- [ ] No `@Autowired` field injection (must use constructor via `@RequiredArgsConstructor`)
- [ ] Service read methods that traverse lazy fields have `@Transactional(readOnly = true)`
- [ ] No secrets/passwords hardcoded
- [ ] No string concatenation in JPQL/native queries
- [ ] A `TenantEntity` repository overrides `findById` with an explicit `@Query` — Hibernate
      `@Filter` does not apply to `EntityManager.find()` (`docs/COMMON_MISTAKES.md` #1)
- [ ] No `Map.of(...)` passed to `OutboxEventRecorder`/audit `record(...)` calls (`docs/COMMON_MISTAKES.md` #3)

### HIGH (should fix before merge)
- [ ] Controller injects only services (no repository injection)
- [ ] All `@RequestBody` params have `@Valid`
- [ ] New public endpoints are an explicit addition to `SecurityConfig`, not an accidental fallthrough
- [ ] Mutating endpoints carry `@RequirePermission`
- [ ] Write methods have `@Transactional`; read methods (with lazy traversal) have `@Transactional(readOnly = true)`
- [ ] DTOs are Java records in `<module>.dto` — no manual mapping omissions
- [ ] Domain-specific exceptions + `ErrorType` used (no raw error strings)
- [ ] Own-resource check in service for user-scoped operations (e.g. parent-owns-child)
- [ ] Action paths are slash-style, never `:verb` (ADR-006)

### MEDIUM
- [ ] Method length < 50 lines
- [ ] No `FetchType.EAGER` on associations
- [ ] Validation/response messages have both `messages_ar.properties` and `messages_en.properties` entries
- [ ] `@Enumerated(EnumType.STRING)` on all enum entity fields
- [ ] Money fields are `BigDecimal`/`NUMERIC` — never `double`/`float`
- [ ] Timestamps are `Instant` — never `LocalDateTime`
- [ ] New FKs to `users(id)`/`schools(id)` use `ON DELETE CASCADE`

### SCHOOLBRIDGE-SPECIFIC
- [ ] Fan-out to many recipients uses `repository.saveAll(list)`, not a per-row loop
      (`AnnouncementServiceImpl.materializeRecipients` is the reference)
- [ ] A notification-channel stub in `integrations` reports failure, not success, if it didn't
      actually send anything (`docs/COMMON_MISTAKES.md` #15)
- [ ] Cross-module side effects go through an outbox row + RabbitMQ consumer, not a direct call into
      another module's dispatch logic
- [ ] WhatsApp webhook (or any webhook) verifies its signature before trusting the payload
- [ ] Assistant tools (`assistant/tools/*`) call the existing domain service, never reimplement its
      logic (ADR-005)

## Output Format

Report findings grouped by severity (CRITICAL → HIGH → MEDIUM → LOW).
For each finding: file, line number, issue description, suggested fix.
End with: "✓ No critical issues" or list all critical issues that must be fixed.
