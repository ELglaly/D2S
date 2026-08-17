---
name: security-audit
description: Security audit for SchoolBridge Spring Boot APIs. OWASP Top 10 checklist, JWT validation, tenant isolation, RLS, WhatsApp webhook signature security, input validation, and injection prevention.
metadata:
  version: "2.0.0"
  domain: security
  triggers: security review, security audit, OWASP, vulnerability, before commit
  role: auditor
  scope: security
  output-format: checklist
---

# Security Audit Skill (SchoolBridge)

## Pre-Commit Security Checklist

### Authentication & Authorization
- [ ] JWT validated (signature + expiry) on every request
- [ ] Mutating endpoints carry `@RequirePermission`
- [ ] New public endpoints are an explicit, deliberate `SecurityConfig` addition — never
      accidentally left open
- [ ] Password reset / OTP tokens: single-use, short expiry, stored hashed
- [ ] OTP requests are rate-limited (`OtpRequestRateLimiter`)

### Tenant Isolation
- [ ] Every `TenantEntity` repository overrides `findById` with an explicit `@Query`
      (`docs/COMMON_MISTAKES.md` #1) — the highest-severity, easiest-to-miss class of bug in this
      codebase
- [ ] `TenantEntityArchUnitTest` passes
- [ ] Row-Level Security policies wrap `current_setting(...)` in `nullif(..., '')` to fail closed
- [ ] Cache keys for anything tenant-scoped include the school id — an unscoped cache key is a
      cross-tenant leak, not just staleness

### Input Validation
- [ ] `@Valid` on all `@RequestBody` parameters in controllers
- [ ] `@NotBlank`, `@NotNull`, `@Size` on all DTO fields
- [ ] Enum conversion validated (invalid enum string → 400 not 500)
- [ ] Upload paths sanitized (no path traversal) — attachment uploads go through presigned URLs,
      not a raw multipart-to-filesystem write
- [ ] Pagination parameters bounded (max page size enforced)

### SQL Injection
- [ ] All queries use JPQL parameters (`:param`) or `@Param` — no string concatenation
- [ ] Native queries use named params, not string formatting
- [ ] No raw `EntityManager.createNativeQuery("SELECT ... " + userInput)`

### WhatsApp Webhook Security
- [ ] Signature verified (`WebhookSignatureVerifier`, `WHATSAPP_APP_SECRET`) **before** the payload
      is trusted
- [ ] Webhook endpoint has no JWT requirement (it can't authenticate as a user) but the HMAC check
      is mandatory
- [ ] Handler is idempotent on the provider's event id (Meta retries on anything but a prompt 200)
- [ ] The GET verification-challenge endpoint returns a plain string, not the `ApiResponse`
      envelope — `ApiResponseBodyAdvice.supports()` must check both the Jackson converter type and
      the `com.schoolbridge.api` package, or this breaks (`docs/COMMON_MISTAKES.md` #7)

### Attachment / Storage Security
- [ ] Presigned PUT sets `contentLength` before signing (no equivalent to POST's
      `content-length-range` for PUT — `docs/COMMON_MISTAKES.md` #13)
- [ ] `S3Presigner` uses the same `endpointOverride`/`pathStyleAccessEnabled` as the `S3Client`
      (`docs/COMMON_MISTAKES.md` #14)
- [ ] MIME sniffing catches a declared-vs-actual mismatch before marking an attachment `CLEAN`
- [ ] AV scan result (`AvResult`) is distinguishable from "never scanned" (`SKIPPED`) — don't let a
      disabled scanner silently look identical to a clean result

### Sensitive Data
- [ ] No secrets in `application.yml` or committed files
- [ ] `application-local.yml` is in `.gitignore`
- [ ] `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`, `AES_KEY`, `BLIND_INDEX_KEY`, `WHATSAPP_APP_SECRET`,
      `WHATSAPP_ACCESS_TOKEN`, `OPENAI_API_KEY`, `STORAGE_SECRET_KEY`, `DB_PASSWORD` all loaded from
      environment variables
- [ ] Error responses do NOT include stack traces, SQL details, or internal paths
- [ ] Passwords/PII not logged, not returned in any API response — PII columns encrypted with
      AES-GCM, deterministic blind index for equality lookups only where actually needed

### CORS
- [ ] CORS `allowedOrigins` is not `*` in production profile
- [ ] Only required HTTP methods are allowed

### CSRF
- [ ] CSRF disabled in `SecurityConfig` (acceptable for stateless JWT API)
- [ ] No session-based auth (stateless confirmed)

## Critical Security Patterns in SchoolBridge

### Own-Resource / Row-Ownership Check
```java
// In service, verify the resource belongs to (or is reachable by) the current caller —
// narrower than a Permission, implemented as @PreAuthorize alongside the aspect
if (!parentStudentLinkRepository.existsByParentUserIdAndStudentId(callerId, studentId)) {
    throw new AccessDeniedException("Not linked to this student");
}
```

### Safe Error Response
The global exception handler maps exceptions to `ErrorType` (which pairs an `HttpStatus` with an
i18n key) — never leak `ex.getMessage()` from an unexpected exception straight into a response body.

### JWT Validation (must check both)
1. Signature valid (using `JWT_PUBLIC_KEY`)
2. Token not expired
