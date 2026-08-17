---
description: Run a security audit on SchoolBridge code — OWASP Top 10, JWT, tenant isolation, RLS, webhook signatures, input validation, secrets.
argument-hint: [file-path or leave blank for full audit]
---

Run a security audit on the SchoolBridge project.

Scope: $ARGUMENTS

## Security Audit Checklist

### 1. Secrets & Configuration
- [ ] No API keys, JWT keys, or passwords in committed files
- [ ] `application-local.yml` is in `.gitignore`
- [ ] Environment variables used for: `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`, `AES_KEY`,
      `BLIND_INDEX_KEY`, `WHATSAPP_APP_SECRET`, `WHATSAPP_ACCESS_TOKEN`, `OPENAI_API_KEY`,
      `STORAGE_SECRET_KEY`, `DB_PASSWORD`

### 2. Authentication (JWT)
- [ ] JWT filter validates signature + expiry on every request
- [ ] Refresh token flow invalidates the old token on use
- [ ] OTP requests are rate-limited (`OtpRequestRateLimiter`)
- [ ] `UsernamePasswordAuthenticationToken` is constructed via its 3-arg ctor and never has
      `setAuthenticated(true)` called on it afterward (`docs/COMMON_MISTAKES.md` #2)

### 3. Authorization
- [ ] Every non-public endpoint requires a JWT
- [ ] Mutating endpoints carry `@RequirePermission`; `SecurityConfig`'s public list is minimal —
      only truly public endpoints (auth login/refresh, parent OTP request/verify, WhatsApp webhook)
- [ ] Own-resource check in service layer beyond the permission (e.g. `parentLinkedTo(studentId)`,
      `teacherTeaches(classId)`) — narrower than a `Permission`, implemented as `@PreAuthorize`
- [ ] `MANAGE_ROLES`/permission-admin endpoints are `SUPER_ADMIN`-only

### 4. Tenant Isolation
- [ ] Every `TenantEntity` repository overrides `findById` with an explicit `@Query`
      (`docs/COMMON_MISTAKES.md` #1) — the Hibernate `@Filter` does not cover `EntityManager.find()`
- [ ] `TenantEntityArchUnitTest` passes
- [ ] Row-Level Security policies use `nullif(current_setting('app.current_tenant', true), '')::uuid`
      to fail closed, not `current_setting(..., true)::uuid` directly (empty-string trap after a
      pooled-connection reset)
- [ ] Any test asserting RLS isolation runs under `SET LOCAL ROLE` onto an unprivileged role, not
      the default Testcontainers superuser connection (superusers bypass RLS unconditionally)

### 5. Input Validation
- [ ] `@Valid` on all `@RequestBody` in controllers
- [ ] Enum deserialization: invalid values return 400 (not 500)
- [ ] Pagination bounded (max page size enforced)
- [ ] No user-controlled input used in file paths, shell commands, or SQL strings

### 6. Webhook Security (WhatsApp)
- [ ] `WebhookSignatureVerifier` runs and the signature is checked **before** the payload is trusted
- [ ] Handler is idempotent on the provider's event id
- [ ] Verification-challenge GET returns a plain string, not the `ApiResponse` envelope
      (`ApiResponseBodyAdvice.supports()` must check both the Jackson converter type and the
      `com.schoolbridge.api` package — `docs/COMMON_MISTAKES.md` #7)

### 7. Attachments / Storage
- [ ] Presigned PUT sets `contentLength` on the request before signing (content-length-range is
      POST-only, doesn't exist for PUT) — `docs/COMMON_MISTAKES.md` #13
- [ ] `S3Presigner` carries the same `endpointOverride`/`pathStyleAccessEnabled` as the `S3Client` —
      mismatched signing host looks like bad credentials and isn't (`docs/COMMON_MISTAKES.md` #14)
- [ ] MIME sniffing catches a declared-vs-actual content type mismatch before marking `CLEAN`

### 8. Money & Notifications
- [ ] All money stored as `NUMERIC`/`BigDecimal` (never `double`/`float`)
- [ ] `Map.of(...)` never used for outbox/audit payloads (nullable-field NPE — `docs/COMMON_MISTAKES.md` #3)
- [ ] A notification-channel stub reports failure, not success, when it hasn't actually sent
      anything (`docs/COMMON_MISTAKES.md` #15)

### 9. Error Responses
- [ ] No stack traces in API responses
- [ ] No SQL error messages in API responses
- [ ] No internal file paths in API responses
- [ ] 401/403 return generic messages (not "user X not found")

### 10. SQL Injection
```bash
grep -rn "createNativeQuery\|createQuery\|nativeQuery" src/main/java/ | grep '+'
```
All results should use parameterized queries, not string concatenation.

## Output
Report any findings with: file, line, severity (CRITICAL/HIGH/MEDIUM), and remediation.
