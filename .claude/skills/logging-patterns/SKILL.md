---
name: logging-patterns
description: SLF4J and structured-logging conventions already wired in SchoolBridge (logback-spring.xml, MDC keys, logstash JSON in prod). Use when adding logging, debugging via logs, or touching logback-spring.xml.
---

# Logging Patterns (SchoolBridge)

The setup already exists — this skill is about using it correctly, not
setting it up from scratch.

## What's already configured (`src/main/resources/logback-spring.xml`)

- **`local`/`test` profiles**: human-readable console pattern —
  `%d{HH:mm:ss.SSS} %-5level [%X{traceId:-},%X{spanId:-}] [school=%X{schoolId:-} user=%X{userId:-}] %logger{36} - %msg%n`
- **`prod` profile**: `LogstashEncoder` (JSON), with MDC keys `traceId`,
  `spanId`, `schoolId`, `userId` included, plus a static `service` field.
- **`RequestIdFilter`** (`common/web`) binds a `requestId` MDC key for the
  duration of each request (from an inbound header or a fresh UUID), and
  echoes it back as a response header.

  > **Known gap**: `requestId` is bound to MDC but is *not* in the prod
  > `LogstashEncoder`'s `includeMdcKeyName` list, so it currently doesn't
  > appear in production JSON logs even though it's set. Worth fixing if
  > you're touching `logback-spring.xml` for something else — not fixing
  > proactively as a drive-by.
- Tracing is via Micrometer + OpenTelemetry (`traceId`/`spanId` come from
  there, not manually set).

## SLF4J usage

```java
private static final Logger log = LoggerFactory.getLogger(HomeworkServiceImpl.class);
```

Always parameterized, never concatenated — concatenation runs even when the
log level is disabled:

```java
// GOOD
log.debug("Publishing homework {} for class {}", homeworkId, classId);

// BAD — always builds the string
log.debug("Publishing homework " + homeworkId + " for class " + classId);
```

## What never goes in a log line

- Decrypted PII (anything behind `AesGcmAttributeConverter`) — log the
  entity id, never the plaintext field.
- JWTs, refresh tokens, OTPs, WhatsApp/webhook secrets.
- Full request/response bodies for endpoints carrying the above.

This is stricter than "don't log secrets" generically — SchoolBridge
specifically encrypts certain PII at rest (`common/crypto`), and logging the
decrypted value defeats that encryption for anyone with log access.

## MDC in new code

Don't hand-roll new MDC keys for things `RequestIdFilter`/tenant context
already provide (`requestId`, `schoolId`, `userId`, `traceId`, `spanId`).
For a genuinely new cross-cutting field (e.g. a batch-job run id in
`HomeworkReminderSweeper`), bind it the same way — `MDC.put` before the
unit of work, `MDC.remove` in a `finally`, and add it to
`logback-spring.xml`'s prod `includeMdcKeyName` list in the same change or
it silently won't reach the JSON output (see the `requestId` gap above).

## Reading structured logs

In prod (JSON), pipe through `jq` rather than grep:

```bash
# Errors only
cat app.log | jq 'select(.level == "ERROR")'

# Follow one request end-to-end
cat app.log | jq --arg id "$REQUEST_ID" 'select(.requestId == $id)'
```

Locally (`local`/`test` profile), the human-readable pattern already prints
`traceId`/`spanId`/`schoolId`/`userId` inline — no `jq` needed for
day-to-day debugging.
