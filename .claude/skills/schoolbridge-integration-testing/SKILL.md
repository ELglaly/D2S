---
name: schoolbridge-integration-testing
description: Write integration tests in SchoolBridge's actual style — singleton Testcontainers, RestAssured + minted JWTs, cross-tenant isolation tests, ArchUnit, WireMock. Use when adding tests for a new endpoint, repository, consumer, or external adapter.
---

# SchoolBridge: integration testing

All integration tests extend `AbstractIntegrationTest`
(`src/test/java/com/schoolbridge/api/AbstractIntegrationTest.java`) and are
named `*IntegrationTest.java` (repository isolation tests are named
`*RepositoryIsolationTest.java`), in the same package as the code under
test — no separate `integration/` subtree.

## Base setup — read this before writing any test

`AbstractIntegrationTest` starts Postgres (`pgvector/pgvector:pg16`,
compatible-substitute for `postgres`), RabbitMQ, and Redis via
`@ServiceConnection` in a **static initializer, once per JVM, never
stopped** (the singleton-container pattern — the JUnit `@Container`
extension would tear containers down between classes, invalidating
connection details already wired into cached Spring contexts). Every
integration test class just extends it and gets `@ActiveProfiles("test")`
for free; don't add your own `@Testcontainers`/`@Container` annotations.

Because the database is shared across the whole test run, **every test
class cleans up its own rows in `@BeforeEach`** — there are no SQL fixture
files or per-test transaction rollback. Use a `TransactionTemplate`
(`@Autowired TransactionTemplate tx`), delete children before parents (FK
order), and clean at both ends if the entity persists across methods:

```java
@BeforeEach
void setUp() {
  tx.executeWithoutResult(s -> homeworkItemRepository.deleteAll());
  tx.executeWithoutResult(s -> classRepository.deleteAll());
  tx.executeWithoutResult(s -> userRepository.deleteAll());
  tx.executeWithoutResult(s -> schoolRepository.deleteAll());
  // ... then persist this test's fixture rows, also via tx.execute(...)
}
```

## HTTP-level tests: RestAssured + minted JWTs

Not MockMvc. `@SpringBootTest(webEnvironment = RANDOM_PORT)`,
`@LocalServerPort int port`, set `RestAssured.port = port` in
`@BeforeEach`. Mint tokens directly via the real `JwtService` — no fake
principal, no `@WithMockUser`:

```java
@Autowired JwtService jwtService;

private String staffToken(String role) {
  return jwtService.issueAccess(
      UUID.randomUUID().toString(),
      Map.of("kind", "USER", "role", role, "schoolId", UUID.randomUUID().toString()));
}
```

```java
given()
    .header("Authorization", "Bearer " + staffToken("TEACHER"))
    .when()
    .delete("/api/v1/grades/" + UUID.randomUUID())
    .then()
    .statusCode(403)
    .body("type", equalTo("https://schoolbridge.app/errors/authorization"));
```

Cover, per endpoint: no token → 401; wrong role/permission → 403 with the
RFC 7807 `type` field; right role but missing resource → 404 (proves the
authz aspect runs *before* the handler body, not instead of a real check).
`GradesAuthorizationIntegrationTest` is the reference.

## Cross-tenant isolation tests (repository level)

Required for every `TenantEntity` repository (`schoolbridge-tenant-entity`
skill). Persist two schools, one row per school, bind `TenantContext`, and
assert the filter excludes the other school's data — **always** clear it in
`@AfterEach`:

```java
@AfterEach
void tearDown() {
  TenantContext.clear();
}

@Test
void findById_underTenantA_cannotSeeHomeworkInB() {
  TenantContext.set(schoolA);
  var own = tx.execute(s -> homeworkItemRepository.findById(homeworkInA));
  var other = tx.execute(s -> homeworkItemRepository.findById(homeworkInB));
  assertThat(own).isPresent();
  assertThat(other).as("school A must not see school B's homework").isEmpty();
}
```

Test every custom finder too, not just `findById` — see
`HomeworkItemRepositoryIsolationTest.findFiltered_isFilteredByTenant`.
`HomeworkItemRepositoryIsolationTest` / `UserRepositoryIsolationTest` are
the reference shape.

## Consumer / async isolation tests

A RabbitMQ consumer or scheduled sweeper runs with **no inbound HTTP
request**, so there's no `TenantContext` bound by a filter — the consumer
code itself must `TenantContext.runAs(schoolId, ...)` around its work.
Prove this with the same two-school pattern, but drive it through the
consumer/service entry point instead of an HTTP call —
`ConsumerCrossTenantIsolationTest` /
`AttendanceAlertConsumerCrossTenantIsolationTest` are the reference.

## External adapter tests

For a `RestClient`-backed adapter (WhatsApp, any future cloud API),
`@WireMockTest`-style wire-shape tests — see
`MetaCloudWhatsAppClientWireMockTest`. Remember the adapter must build its
`RestClient` with `SimpleClientHttpRequestFactory`
(`docs/COMMON_MISTAKES.md` #4) or the test connection resets mid-write on
Windows. For tests that don't need real wire-format assertions, a hand-
written fake (`FakeWhatsAppClient`) wired as `@Primary` is lighter than
WireMock — use whichever the existing tests in that area already use.

## Structural tests (ArchUnit)

Enforce invariants that are easy to violate by omission, like "every
tenant entity extends `TenantEntity`":

```java
@AnalyzeClasses(packages = "com.schoolbridge.api")
class TenantEntityArchUnitTest {
  @ArchTest
  static final ArchRule domain_entities_with_school_id_extend_tenant_entity =
      classes().that().areAnnotatedWith(Entity.class)
          .and().resideOutsideOfPackages("..common..")
          .and(carry_school_id_column())
          .should().beAssignableTo(TenantEntity.class)
          .because("...");
}
```

`TenantEntityArchUnitTest` is the only one today; add a new one for any
similarly easy-to-forget structural rule rather than relying on code review
to catch it every time.

## Oracle tests (exhaustive parity)

For a catalog where every entry needs the *exact right* access shape (the
assistant's full tool catalog, currently), write one test that asserts
role parity across **every** entry, not spot checks —
`AssistantToolAuthorizationOracleTest` is the reference. This is the
pattern to reach for whenever "did we get the right role on every one of
N things" is the actual risk, since a few example-based tests can't catch
one wrong entry among many.

## Before calling a test suite done

- Run `/verify` — Spotless + SpotBugs + the full suite, not just the new
  test class.
- New `TenantEntity` → isolation test exists and covers every custom
  finder, not just `findById`.
- New permission-gated endpoint → 401/403/404 triad covered.
- Check `docs/COMMON_MISTAKES.md` and
  `docs/PORTABLE_ENGINEERING_LESSONS.md` for anything relevant to what
  you're testing (JPA insert/delete ordering, `@Transactional` self-call,
  timestamp precision mismatches) before writing an assertion that could
  be flaky for one of those reasons.
