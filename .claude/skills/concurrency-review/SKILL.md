---
name: concurrency-review
description: Thread safety review for SchoolBridge. Covers @Async, @Transactional isolation, outbox/webhook idempotency, RLS + tenant context, and RabbitMQ consumer isolation.
metadata:
  version: "2.0.0"
  domain: concurrency
  triggers: concurrency, thread safety, @Async, race condition, idempotency
  role: reviewer
  scope: concurrency
  output-format: checklist
---

# Concurrency Review Skill (SchoolBridge)

## Thread Safety Checklist

### Spring Beans (Singletons)
- [ ] No mutable instance fields in `@Service`, `@Repository`, `@Controller`, `@Component`
- [ ] Constructor injection with `final` fields (immutable after construction)
- [ ] No static mutable state

### Tenant Context Across Threads
- [ ] `TenantContext` is request-scoped (typically a `ThreadLocal`) — anything moved to a
      `@Scheduled` sweeper or a `@RabbitListener` consumer thread must **not** rely on it being
      populated; those paths resolve the tenant explicitly from the row/message, not from a filter
- [ ] Test cleanup that runs `deleteAll()` across schools calls `TenantContext.clear()` first — a
      tenant left bound from a previous test silently filters the delete to one school and the next
      test's insert then fails on a leftover FK

### @Async / @Scheduled Usage
- [ ] `@Async`/`@Scheduled` methods handle their own exceptions — they don't propagate to a caller
      that's already gone
- [ ] Sweepers (e.g. the homework reminder sweeper, the attachment retention sweeper) are safe to
      run more than once concurrently across instances — dedup via a DB flag/timestamp
      (`reminderSentAt`), not an in-memory lock, since this can run on more than one instance
- [ ] `@Transactional` is on the service method the sweeper calls, not on the `@Scheduled` method
      itself

### @Transactional Isolation
- [ ] Default isolation (`READ_COMMITTED`) is appropriate for most operations
- [ ] `@Transactional` on service, NOT on controller
- [ ] The outbox row and its triggering domain write commit in the **same transaction** — this is
      what makes the outbox pattern safe under concurrent requests; splitting them reintroduces the
      dual-write problem the pattern exists to avoid

### Outbox / Webhook Idempotency
- [ ] Outbox payloads use `HashMap`, never `Map.of(...)` — a nullable field NPEs mid-transaction,
      which can look like a concurrency bug when it's actually deterministic
      (`docs/COMMON_MISTAKES.md` #3)
- [ ] The WhatsApp inbound webhook handler is idempotent on the provider's event id — Meta retries
      on anything but a prompt 200, so the same event can arrive more than once
- [ ] A RabbitMQ consumer that processes an outbox event and then writes a side effect (e.g. marking
      a notification sent) does so idempotently — redelivery after a broker-visible-but-unacked
      message is a normal occurrence, not an edge case

### Row-Level Security Under Concurrency
- [ ] `current_setting('app.current_tenant', true)` is set per-transaction/connection, not assumed
      to persist correctly across a pooled connection being reused by a different request — always
      wrap reads of it in `nullif(..., '')` to fail closed rather than leak or 500
      (`docs/COMMON_MISTAKES.md` #11)

### Attachment Pipeline Concurrency
- [ ] Two `complete` calls racing on the same attachment id don't double-charge storage/quota —
      check status transition is a single atomic update (`PENDING → UPLOADED`), not read-then-write
- [ ] The retention sweeper deleting abandoned `PENDING` rows doesn't race a client's in-flight
      `complete` call in a way that deletes a row the client is about to reference — a reasonable
      abandonment threshold (well past any realistic upload time) is the mitigation, not a lock

### Rate Limiting
- [ ] OTP request rate limiting (`OtpRequestRateLimiter`, Redis-backed) is atomic under concurrent
      requests from the same phone/user — a check-then-increment race defeats the limit

## Race Condition Scenarios in SchoolBridge

| Scenario | Risk | Mitigation |
|----------|------|-----------|
| Two teachers publish the same homework item concurrently | Duplicate recipient rows / duplicate reminders | Status transition `DRAFT → PUBLISHED` guarded by a single atomic update; recipient materialization checked for existing rows or made idempotent |
| Homework reminder sweeper runs on two instances at once | Duplicate reminder sends | `reminderSentAt` set at the start of the fan-out loop (DB-visible flag), not an in-memory lock |
| Duplicate WhatsApp webhook delivery | Duplicate notification / duplicate processing | Idempotent on the provider's message/event id |
| Concurrent RLS-bound queries on a shared pooled connection | Cross-tenant leak or spurious 500 | `nullif(current_setting(...), '')` fail-closed pattern; tenant context set/cleared per request |
| Attachment `complete` called twice for the same id | Double AV-scan trigger / inconsistent status | Atomic status-transition check inside the write, not a separate read-then-write |
| OTP request flood from one number | Bypassed rate limit | Redis-backed atomic increment, not a read-then-write check |
