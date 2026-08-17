---
name: test-quality
description: REST Assured + JUnit 5 integration test quality for SchoolBridge. Covers naming, AbstractIntegrationTest patterns, tenant isolation tests, and coverage targets.
metadata:
  version: "2.0.0"
  domain: testing
  triggers: test quality, write tests, integration test, JUnit, REST Assured, coverage
  role: specialist
  scope: testing
  output-format: checklist
---

# Test Quality Skill (SchoolBridge)

## Test Quality Checklist

### Structure
- [ ] Test class extends `AbstractIntegrationTest`
- [ ] `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`
- [ ] `RestAssured.port = port;` set in `@BeforeEach` from an injected `@LocalServerPort`
- [ ] `TenantContext.clear()` at the start of `@BeforeEach` cleanup — a tenant left bound from a
      prior test silently scopes `deleteAll()` to one school and breaks the next test's setup
- [ ] Each test method is independent (no shared mutable state between `@Test` methods)

### Naming
- [ ] Format: `methodName_scenario_expectedBehavior`
- [ ] Examples: `get_shouldReturn404_whenResourceNotFound`,
      `create_shouldReturn401_whenUnauthenticated`,
      `publish_shouldTransitionDraftToPublished_whenCalledByOwningTeacher`

### Coverage per Endpoint (minimum required tests)
- [ ] Happy path (200/201)
- [ ] Unauthenticated (401) — if auth required
- [ ] Insufficient permission (403) — if permission-gated
- [ ] Not found (404) — if resource lookup, **including cross-tenant "not found"**
- [ ] Validation error (400/422) — for each required field
- [ ] Conflict (409) — if uniqueness constraint or state-dependent (e.g. attachment not yet `CLEAN`)

### Tenant Isolation Tests
- [ ] Every new `TenantEntity` repository gets a `*RepositoryIsolationTest` — create under School A,
      assert School B's caller gets nothing back (404 at the HTTP layer, empty `Optional` at the
      repository layer)
- [ ] If the test needs to prove **Row-Level Security** specifically (not just the app-layer
      `@Filter`), it must run under `SET LOCAL ROLE` onto an unprivileged role — the default
      Testcontainers connection is a superuser and bypasses RLS unconditionally

### Assertions (REST Assured)
- [ ] Assert HTTP status code explicitly
- [ ] Assert specific `data.*` fields, not just "something returned"
- [ ] For collections: assert size, then assert content
- [ ] Use `.log().ifValidationFails()` when debugging an unexpected status/body

### Common Mistakes to Avoid
- [ ] Do NOT rely on test execution order unless the class is explicitly ordered
- [ ] Do NOT leave test data without cleanup — `deleteAll()` in the right FK order (children before
      parents), or add `ON DELETE CASCADE` to the migration instead of working around it in tests
- [ ] Do NOT use enum values from memory — grep the real `public enum` file; a doc snapshot can be
      stale
- [ ] Do NOT assume a fixed test-user/BCrypt-hash convention exists — check how the sibling test in
      the same module mints its token (usually `JwtService` directly, see `issueStaffToken`-style
      helpers)
- [ ] Do NOT use `Map.of(...)` in test setup that triggers an outbox write — same NPE risk as
      production code

## REST Assured Patterns

```java
// Status + body field
given()
    .header("Authorization", "Bearer " + token)
    .get("/api/v1/homework/" + id)
    .then()
    .statusCode(200)
    .body("data.status", equalTo("PUBLISHED"));

// Create + extract id
String id =
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(Map.of("classId", classId, "subject", "Read chapter 4", "dueDate", "2026-08-20"))
        .post("/api/v1/homework")
        .then()
        .statusCode(201)
        .extract()
        .path("data.id");

// Validation error
given()
    .header("Authorization", "Bearer " + token)
    .contentType(ContentType.JSON)
    .body(Map.of("subject", "")) // missing required classId/dueDate
    .post("/api/v1/homework")
    .then()
    .statusCode(422);

// Cross-tenant isolation
given()
    .header("Authorization", "Bearer " + tokenSchoolB)
    .get("/api/v1/homework/" + homeworkIdInSchoolA)
    .then()
    .statusCode(404);
```

## Coverage Target

For every new service method:
- At least one positive integration test
- At least one negative integration test (invalid input, unauthorized, or wrong tenant)
- A tenant isolation test if a new `TenantEntity` repository was introduced
