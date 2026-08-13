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

## 13. A presigned **PUT** cannot enforce a maximum upload size

`content-length-range` is a presigned **POST** form-policy condition. There is no
equivalent for PUT, so `presignPutObject` gives you a URL that will happily accept
a body of any length — validating the client's declared size server-side before
minting the URL constrains nothing, because the client is not obliged to send what
it declared.

**Fix:** set `contentLength` on the `PutObjectRequest` before presigning. The SDK
signs it, so the object store rejects any body that disagrees, and the cap is
enforced by S3 rather than by client good behaviour. Return
`PresignedPutObjectRequest.signedHeaders()` to the client — a PUT that omits a
signed header gets a 403 that reads exactly like a credentials failure. Re-check
the real size with `HeadObject` afterwards anyway, for backends whose signature
semantics are laxer than S3's. See `S3ObjectStorage.presignPut`.

## 14. `S3Presigner` must carry the same `endpointOverride` as the client

A SigV4 signature covers the host. Building the `S3Client` against a MinIO or R2
endpoint but leaving the `S3Presigner` on the default AWS resolver produces URLs
signed for `s3.<region>.amazonaws.com`, and the request to the real endpoint fails
with a 403 that looks like bad credentials and is not.

**Fix:** apply `endpointOverride` and `pathStyleAccessEnabled` to *both* beans —
`StorageConfig` does. In tests this also means the presigner has to be pointed at
the Testcontainer's mapped port, which is only known at runtime, so
`@DynamicPropertySource` rather than a static property.

## 15. A dev stub that reports success ends a first-match-wins fallback chain

**Symptom:** in an environment where an optional provider is not configured, users
stop receiving notifications entirely. Nothing errors. Logs look busy and healthy.

**Cause:** `NotificationDispatcher.dispatch(UserDispatchRequest, channels)` walks the
channel list and **stops at the first channel that accepts**. `LoggingPushClient` —
the no-op that stands in when `schoolbridge.push.fcm.enabled=false` — used to return
`new PushSendResult(true, "stub-" + nanoTime())`. Harmless while push had no caller.
The moment push became the first entry in `NotificationChannel.DEFAULT_ORDER`, any
user with a registered device got their notification "delivered" to a log line, and
WhatsApp was never tried.

The stub was written before the code that consumed it, and it was written to look
like the success path rather than to tell the truth.

**Fix:** a stub for a channel in a fallback chain must report **not accepted**. It
did not send anything, so it must not claim to have. `LoggingPushClient` now returns
`new PushSendResult(false, null)`, the walk falls through to WhatsApp, and
`push.send.failure` climbing with `push.send.success` at zero is the operational
signal that FCM is unconfigured. `LoggingPushClientTest` is a one-assertion guard
against a silent total outage.

**Generalise:** whenever a no-op adapter feeds a "try until one works" loop, its
return value is a control-flow decision, not a formality. Ask what the loop does with
`true` before writing it.

## Known, accepted risk (not a bug to "fix" reflexively)

- `docs/CODE_REVIEW.md` M2 — tenant `findById` isolation depends on the
  Hibernate filter being active (bound tenant + active transaction), not an
  explicit `school_id` predicate in the JPQL. Existing isolation tests cover
  the happy paths; this is defense-in-depth to raise, not a confirmed
  bypass, if you're touching that code path.
