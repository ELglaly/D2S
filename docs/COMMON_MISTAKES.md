# SchoolBridge — Common Mistakes

> Every entry here cost real debugging time (see the linked memory file for
> the incident). Read this before touching the areas listed — cheaper than
> repeating the mistake.

## 1. Tenant `findById` bypasses the isolation filter

Hibernate `@Filter` only applies to *queries* (HQL/JPQL/criteria), not to
`EntityManager.find()`. Spring Data's default `findById` calls `em.find()`,
so an unmodified `findById` on a `TenantEntity` repo lets a caller in school
A load a row from school B by ID.

**Fix:** every `TenantEntity` repository overrides `findById` with an
explicit `@Query`:

```java
@Override
@Query("select h from HomeworkItem h where h.id = :id")
Optional<HomeworkItem> findById(@Param("id") UUID id);
```

`HomeworkItemRepository` / `UserRepository` are canonical examples. Add the
matching cross-tenant-invisibility test (two schools, assert `findById`
can't see the other school's row) for any new repo.
→ `feedback_hibernate_filter_findbyid_bypass.md`

## 2. `UsernamePasswordAuthenticationToken.setAuthenticated(true)` throws

The 3-arg constructor (`principal, credentials, authorities`) already marks
the token authenticated internally. Calling `setAuthenticated(true)`
afterward throws — and because it's not an `AuthenticationException`, it
escapes auth-filter catch blocks, becomes a 500, and (since
`OncePerRequestFilter` skips the error dispatch by default) re-enters the
entry point on `/error`, producing a misleading 401 with
`instance="/error"` that looks like a rejected JWT.

**Fix:** construct via the 3-arg ctor and return — never call
`setAuthenticated` afterward. If you see a 401 with `instance="/error"`,
suspect a non-`AuthenticationException` thrown inside the filter chain
before suspecting the token itself.
→ `feedback_springsecurity_uptoken_trap.md`

## 3. `Map.of(...)` NPEs on outbox/audit payloads

Outbox and audit payloads carry naturally-nullable fields (`attachmentKey`,
`scheduledFor`, `parentResponse`, …). `Map.of(...)` throws NPE on the first
null value — and the exception gets swallowed by the framework before it
reaches an obvious layer, so a 500 shows up as an unrelated-looking test
failure ("expected 201 was 500").

**Fix:** always build these payloads with `HashMap` (or `LinkedHashMap` for
log-order readability), `put(...)` every key including ones you're sure are
non-null today. Treat any `Map.of(` in a call to `outbox.record(...)` or
`auditService.record(...)` as a review red flag.
→ `feedback_outbox_audit_mapof_npe.md`

## 4. RestClient default factory aborts mid-write on Windows

Spring `RestClient`'s default JDK-HttpClient-backed factory resets the
connection mid-write against Jetty-12-backed test servers (WireMock) on
Windows JDK 23 — `IOException: An established connection was aborted`,
request never reaches the server.

**Fix:** for synchronous server-to-server adapters, pass
`SimpleClientHttpRequestFactory` explicitly:

```java
SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
factory.setConnectTimeout(5_000);
factory.setReadTimeout(15_000);
builder.baseUrl(base).requestFactory(factory).build();
```

For production traffic wanting HTTP/2, use Apache HttpClient5
(`HttpComponentsClientHttpRequestFactory`) instead of falling back to the
JDK factory.
→ `feedback_restclient_jdk_factory_windows.md`

## 5. RestClient `baseUrl` + leading-slash path drops the base path

`.baseUrl("https://host/v1")` + `.uri("/chat/completions")` hits
`https://host/chat/completions` — `DefaultUriBuilderFactory` **replaces**
the base path, it doesn't append. `/v1` silently disappears; the failure
only shows up as a 404 at the first real call.

**Fix:** for any base URL with a path prefix (`/v1`, `/v20.0`, …), either
build and POST an absolute URL, or ensure the base ends with `/` **and**
the request path has no leading `/`. Don't assume `baseUrl + "/path"`
concatenates.
→ `feedback_restclient_baseurl_leading_slash_drops_path.md`

## 6. AIP colon-verb paths don't survive real clients

`POST /resource:action` gets percent-encoded to `%3A` by RestAssured, OkHttp
default builders, curl, and Postman's path-variable input. Spring's handler
mapping doesn't match `%3A` against a `:action` mapping, so the request
falls through to the static-resource handler → `NoResourceFoundException` →
500. Looks like a service-layer crash, is actually a routing miss.

**Fix:** use slash-style action paths (`/attendance/mark`,
`/schools/{id}/suspend`), never `path:verb`. `AttendanceController` is the
canonical slash-style example.
→ `feedback_aip_colon_paths_dont_survive_clients.md`

## 7. `ResponseBodyAdvice.supports()` needs two guards, not one

The envelope-wrapping advice must check **both**:

1. Converter type — `MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType)`,
   or `ResponseEntity<String>` controllers (e.g. the WhatsApp webhook GET
   challenge) select `StringHttpMessageConverter` and the cast throws → 500.
2. Declaring-class package — `returnType.getDeclaringClass().getName().startsWith("com.schoolbridge.api")`,
   or Actuator endpoints get wrapped too and `/actuator/health` tests that
   assert `status == UP` break because it moves under `data.status`.

**Fix:** both guards, always, before the wrapping logic. See
`ApiResponseBodyAdvice.supports()` for the canonical form.
→ `feedback_response_body_advice_exclusions.md`

## 8. Missing `ON DELETE CASCADE` breaks existing test teardown

Any new child table FK-referencing `users(id)` or `schools(id)` without
`ON DELETE CASCADE` breaks every existing integration test whose
`@BeforeEach` does `userRepository.deleteAll()` — `DataIntegrityViolationException`
on the new child table, because those tests predate the new table and don't
know to delete its rows first.

**Fix:** always cascade FKs to `users(id)` / `schools(id)` in new
migrations. `008-device-tokens.sql` is the pattern.
→ `feedback_device_token_fk_cascade.md`

## 9. Spotless strips an import added before its first use

The Spotless `removeUnusedImports` post-edit hook runs after every file
write. Add an `import` in one edit and its first usage in a *later* edit,
and the hook strips the import in between → "cannot find symbol" on the
next compile.

**Fix:** add the import and its usage in the same edit. For genuine
one-offs (an annotation used once), a fully-qualified name sidesteps the
issue entirely.

## 10. SpotBugs flags `\n` inside `.formatted(...)`

`VA_FORMAT_STRING_USES_NEWLINE` fires when a text block containing a
literal `\n` is passed to `String.format`/`.formatted(...)`.

**Fix:** for multi-line templates with placeholders, use
`template.replace("{x}", v)` instead of `.formatted(...)`.

## 11. `current_setting(gkey, true)` returns `''`, not NULL, after a reset

An RLS policy written as
`school_id = current_setting('app.current_tenant', true)::uuid` looks like it
fails closed when no tenant is bound. It does — the *first* time. Once
`set_config(..., true)` has run once on that connection, the GUC is reset to an
empty **string**, not to unset, and `''::uuid` raises
`invalid input syntax for type uuid: ""`. On a pooled connection that means the
second unbound query of a session 500s instead of returning nothing.

**Fix:** `nullif(current_setting('app.current_tenant', true), '')::uuid`. NULL
matches no row, which is the fail-closed behaviour you wanted. See changelog
`017-tenant-rls.sql`.

## 12. Testcontainers connects as a superuser, so RLS tests prove nothing

`PostgreSQLContainer` sets `POSTGRES_USER`, which makes that role the bootstrap
**superuser**. Superusers bypass row-level security unconditionally — `ALTER
TABLE … FORCE ROW LEVEL SECURITY` does *not* subject them, it only subjects the
owner. A test that FORCEs a table and then asserts isolation on the default
connection is asserting nothing and will pass with the policy deleted.

**Fix:** create an unprivileged role and `SET LOCAL ROLE` onto it inside the test
transaction (`SET LOCAL` reverts on commit, so no other test inherits it). The
role and the statement that assumes it live in the `RlsTestRole` test helper;
`TenantRlsIntegrationTest` and `RlsTenantIsolationTest` both use it.

`RlsTenantIsolationTest` (changelog 014) originally used the FORCE approach and
was weaker than its javadoc claimed — its cross-tenant assertion passed because
of the application-side metadata filter, not the policy. It now runs under the
unprivileged role and asserts with raw SQL, so only RLS can be doing the work.

## Known, accepted risk (not a bug to "fix" reflexively)

- `docs/CODE_REVIEW.md` M2 — tenant `findById` isolation depends on the
  Hibernate filter being active (bound tenant + active transaction), not an
  explicit `school_id` predicate in the JPQL. Existing isolation tests cover
  the happy paths; this is defense-in-depth to raise, not a confirmed
  bypass, if you're touching that code path.
