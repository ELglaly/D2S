---
description: Build the SchoolBridge project and diagnose any compilation errors.
argument-hint: [optional: "full" to run all tests, "skip" to skip tests]
---

Build the SchoolBridge Spring Boot project.

Mode: $ARGUMENTS

## Build Steps

1. **Compile**:
   ```bash
   mvnw.cmd -DskipTests compile
   ```

2. **If argument is "full"** — run all tests:
   ```bash
   mvnw.cmd test
   ```
   Note: Run test classes individually if multiple classes are needed (Testcontainers stability —
   the singleton container pattern in `AbstractIntegrationTest` helps, but Docker resource limits
   still apply on a large run).

3. **If argument is "skip"** — package without tests:
   ```bash
   mvnw.cmd package -DskipTests
   ```

4. **Default (no argument)** — full build gate: compile + tests + Spotless check + SpotBugs:
   ```bash
   mvnw.cmd verify
   ```

## Error Diagnosis

If build fails, diagnose using these patterns:

| Error | Root Cause | Fix |
|-------|-----------|-----|
| `LazyInitializationException` | Missing `@Transactional(readOnly=true)` | Add to service read method |
| `Migration failed` | Liquibase SQL error | Check PostgreSQL syntax, enum values (grep the real `public enum` file), column names |
| `Cannot find symbol` | Wrong import or typo | Check class/method exists in `com.schoolbridge.api.*` |
| `Unsatisfied dependency` | Missing `@Service`/`@Component` | Add annotation or `@Bean` config |
| Spotless check failure | Formatting drift | Run `mvnw.cmd spotless:apply` |
| SpotBugs failure | A flagged pattern (e.g. `\n` in `.formatted(...)`) | See `docs/COMMON_MISTAKES.md` #10 |
| `TenantEntityArchUnitTest` fails | New `TenantEntity` missing `@Filter` | Add the annotation per `docs/adr/ADR-002-tenant-isolation.md` |
| `Ambiguous mapping` | Duplicate `@RequestMapping` path | Check `.claude/rules/java/schoolbridge-api-map.md` for collisions |
| `NoResourceFoundException` on a mutating call | `:verb`-style action path | Use slash-style (ADR-006) |
| `ContainerLaunchException` | Docker not running / image not pulled | Start Docker Desktop; `AbstractIntegrationTest` needs Postgres, RabbitMQ, Redis, and MinIO images |

After diagnosing, apply the minimal fix and re-run compile to confirm green.
