# Code Review — SchoolBridge API (full project)

> **Date:** 2026-06-09
> **Reviewer:** Claude Code (security + quality checklist)
> **Scope:** 311 Java files / ~23k LOC, configuration, build, Liquibase migrations.

**Verdict:** High-quality codebase with **one CRITICAL secret-exposure issue that must be
remediated before any push/release.** Everything else is MEDIUM or below.

---

## 🔴 CRITICAL — must fix now

### C1. Live Meta WhatsApp credentials committed to git

`src/main/resources/application-local.yml`

- **L26** — real Graph API access token: `EAAONltZC5QKwBR…6ObTMeJRgjy` (the `EAA…` prefix is a
  genuine Facebook/Meta token).
- **L28** — real app secret: `4bcf1a57cf218d267be4de5bb9846928` (this is the HMAC key that
  authenticates the WhatsApp webhook).
- L27 phone-number-id, L5/L14 DB & RabbitMQ passwords (`wasla`, `schoolbridge`) alongside.

**Why this is CRITICAL, not theoretical:**

1. The file is **tracked and not gitignored** — `.gitignore` only covers `.env*`. It has been in
   the repo since the **first commit** (`053892f`).
2. `application.yml` sets `spring.profiles.default: local`, so **`local` is the active profile by
   default**. Combined with the `${WHATSAPP_ACCESS_TOKEN:EAAO…}` fallback, an environment that
   doesn't set the env var **runs with the real token baked in**.
3. The leaked **app secret** is exactly what `WebhookSignatureVerifier` uses to authenticate
   inbound Meta webhooks — anyone with it can forge delivery/read receipts against your endpoint.

**Remediation (in order):**

1. **Rotate immediately** at Meta (developers.facebook.com → your app → WhatsApp → API Setup):
   regenerate the access token *and* the app secret. Assume both are burned.
2. Replace the hardcoded fallbacks with no-default placeholders, e.g.
   `access-token: ${WHATSAPP_ACCESS_TOKEN:}` and `app-secret: ${WHATSAPP_APP_SECRET:}`
   (matches what `application-prod.yml` already does for DB/crypto). Put real values in a local
   `.env` (already gitignored) or an untracked `application-local.yml`.
3. **Purge from history** — rotation handles the live risk, but the secret stays in every clone
   until you scrub it (`git filter-repo --path src/main/resources/application-local.yml
   --invert-paths`, or BFG) and force-push. Since history is only 2 commits, this is cheap now and
   painful later.
4. Add `application-local.yml` (and `application-*.local.yml`) to `.gitignore`; commit a sanitized
   `application-local.yml.example` instead.

---

## 🟡 MEDIUM

**M1 — Committed dev crypto keys.** `application.yml` L64–65 ship base64 defaults for `aes-key`
(decodes to `schoolbridge-dev-aes-key-32bytes`) and `blind-index-key`. *Mitigated:*
`application-prod.yml` requires `${AES_KEY}`/`${BLIND_INDEX_KEY}` with no default, so prod fails
fast if unset. *Residual risk:* any non-prod/staging running the default profile encrypts PII
(`AesGcmAttributeConverter`) and computes blind indexes with a publicly-known key — that data is
effectively plaintext. Treat these like C1: env-only, no committed default.

**M2 — Tenant `findById` relies on filter activation, not an explicit predicate.** The overrides
(e.g. `StudentRepository`) are `select s from Student s where s.id = :id` with **no `school_id`
clause** — isolation depends entirely on `TenantFilterAspect` enabling the Hibernate filter, which
it does *only when* a tenant is bound **and** `isActualTransactionActive()`. Any tenant-repo finder
invoked outside an active transaction (custom `@Query` methods aren't covered by
`SimpleJpaRepository`'s class-level `@Transactional`) would silently return cross-tenant rows. You
have isolation tests covering the happy paths, so this is defense-in-depth, not a confirmed bypass —
but a belt-and-suspenders explicit `school_id` predicate (or asserting a tx/tenant is present in the
aspect) would make it robust to future callers.

**M3 — Naive CSV parsing.** `StudentServiceImpl.bulkImport` (L145) uses `trimmed.split(",", -1)`. A
quoted field containing a comma (`"Smith, John"`) corrupts every subsequent column. Also, two rows
in the *same* file sharing an `externalId` both pass the `existsByExternalId` pre-check (DB not yet
flushed) and collide at the unique constraint. Use a real CSV reader (Commons CSV / OpenCSV) and
dedupe within the batch.

**M4 — Two methods over the 50-line guideline.** `AttendanceAlertService.dispatchAlert` (96 lines)
and `StudentServiceImpl.bulkImport` (95). Both are readable guard-clause flows, but each has an
extractable inner block (recipient resolution; per-row parse+validate).

---

## 🟢 LOW

- **L1** — `GlobalExceptionHandler:48` `TODO(audit)`: cross-tenant security violations are logged but
  not persisted to the audit log. Wire these into `AuditService` so they're queryable.
- **L2** — CORS uses `allowedHeaders("*")` with `allowCredentials(true)`. Safe because origins are
  explicitly listed (not `*`), but tightening to the headers you actually accept is cleaner.
- **L3** — `management.tracing.sampling.probability: 1.0` (100% sampling) is fine for now but
  expensive under load; lower it in prod.

---

## ✅ Strengths (verified, not assumed)

- **No SQL injection** — zero native queries; every `@Query` is JPQL with named params.
- **Strong auth** — RS256 JWT (signature + issuer + expiry verified; jjwt 0.12 binds to the RSA key
  so alg-confusion is blocked), BCrypt cost 12, refresh tokens are 32-byte `SecureRandom` stored
  only as SHA-256, login rate limiting (5/15min), constant-time comparisons for webhook verify-token
  & HMAC.
- **Crypto correct** — AES-256-GCM with a fresh random 12-byte IV per value and 128-bit tag (no
  nonce reuse), key length validated.
- **Tenant gotcha consistently applied** — all 16 `TenantEntity` repos override `findById`; the 5
  non-tenant repos (audit, outbox, platform-admin, refresh-token, school-root) correctly don't.
- **No info leakage** — catch-all handler returns a localized RFC-7807 message and logs server-side
  only; `server.error.include-message/stacktrace: never`.
- **Authorization** — 55 `@PreAuthorize`; the 4 unguarded controllers are correctly public/HMAC-authed,
  and `DeviceController` derives `userId` from the principal (no IDOR).
- **Input validation** — every request DTO carries jakarta constraints + `@Valid`.
- **i18n** — `messages_en`/`messages_ar` at exact 66/66 key parity.
- **Hygiene** — no file >800 lines, only 3 methods >50, zero `System.out`/`printStackTrace`, zero
  empty catches, zero emoji, 1 TODO. Prod profile disables Swagger, hides errors,
  `ddl-auto: validate`, `open-in-view: false`, actuator health `when_authorized`.

---

## Findings summary

| ID  | Severity | Area            | File                                              | Status |
|-----|----------|-----------------|---------------------------------------------------|--------|
| C1  | CRITICAL | Secrets         | `application-local.yml` L26, L28 (+ L5, L14, L27) | Open   |
| M1  | MEDIUM   | Secrets/crypto  | `application.yml` L64–65                          | Open   |
| M2  | MEDIUM   | Tenant isolation| tenant `*Repository.java` `findById` overrides    | Open   |
| M3  | MEDIUM   | Robustness      | `StudentServiceImpl.bulkImport` L145              | Open   |
| M4  | MEDIUM   | Maintainability | `AttendanceAlertService`, `StudentServiceImpl`    | Open   |
| L1  | LOW      | Audit           | `GlobalExceptionHandler` L48                      | Open   |
| L2  | LOW      | CORS            | `SecurityConfig` L107                             | Open   |
| L3  | LOW      | Observability   | `application.yml` L168                            | Open   |

**Bottom line:** the engineering is strong and the documented traps are genuinely handled. The
blocker is **C1** — rotate the Meta token + app secret, strip the hardcoded fallbacks, and purge
them from history. The MEDIUMs are worth a follow-up pass but aren't release-blocking.
