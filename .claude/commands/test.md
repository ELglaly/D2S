---
description: Run integration tests for a specific SchoolBridge test class or module, with output analysis.
argument-hint: <TestClassName> or <module> e.g. "AttachmentPipelineIntegrationTest" or "homework"
---

Run integration tests for the SchoolBridge project.

Target: $ARGUMENTS

## Steps

1. **Resolve the test class**:
   - If argument is a full class name (e.g. `AttachmentPipelineIntegrationTest`), use it directly
   - If argument is a module name (e.g. `homework`), find the matching test classes:
     `src/test/java/com/schoolbridge/api/<module>/*Test.java`

2. **Run the test**:
   ```bash
   mvnw.cmd test -Dtest=<TestClassName>
   ```
   Note: `AbstractIntegrationTest` uses a **singleton container** pattern (Postgres/RabbitMQ/Redis/
   MinIO start once per JVM and are never torn down), so running several classes together is
   generally fine — but if Docker resources are tight, fall back to running classes individually.

3. **Analyze results**:
   - If all pass: report coverage (count of tests, what scenarios covered)
   - If any fail: show the full error + stack trace for each failure
   - For each failure, identify the root cause using the known patterns:
     - `LazyInitializationException` → missing `@Transactional(readOnly=true)` on service
     - `401` when success expected → check how the test obtains its token (usually
       `JwtService`-minted directly, not via `/auth/login`) rather than assuming a fixture problem
     - `IllegalArgumentException: No enum constant ...` → grep the real `public enum` file for the
       correct value; don't trust a stale doc snapshot
     - Cross-tenant data leakage in a test → missing `findById` `@Query` override on a
       `TenantEntity` repository (`docs/COMMON_MISTAKES.md` #1)
     - Data leakage/`DataIntegrityViolationException` between tests → teardown order (children
       before parents) or a missing `ON DELETE CASCADE` on a new FK to `users(id)`/`schools(id)`
     - `TenantEntityArchUnitTest` failure → a new tenant-scoped entity is missing `@Filter`
     - `ContainerLaunchException`/startup hang → Docker not running, or one of the four images
       (`pgvector/pgvector:pg16`, RabbitMQ, Redis, MinIO) isn't pulled yet
     - RLS isolation test passes trivially → check it's running under `SET LOCAL ROLE` on an
       unprivileged role, not the default Testcontainers superuser connection

4. **Report**:
   - Tests passed: N / Total
   - Tests failed: list with root cause and fix suggestion
   - Any pitfall worth a memory entry, or an addition to `docs/COMMON_MISTAKES.md`?
