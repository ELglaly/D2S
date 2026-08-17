---
name: java-code-review
description: Systematic Java code review checklist for SchoolBridge. Covers correctness, tenant isolation, JPA, security, and performance in one pass.
metadata:
  version: "2.0.0"
  domain: code-quality
  triggers: code review, review Java, review Spring, review controller, review service
  role: reviewer
  scope: code-review
  output-format: checklist
---

# Java Code Review Skill (SchoolBridge)

## Review Severity Levels
- **CRITICAL** — Must fix before merge (security, tenant data leak, NPE in prod)
- **HIGH** — Should fix before merge (wrong behavior, test failure risk)
- **MEDIUM** — Fix if quick (code smell, missing validation, minor bug)
- **LOW** — Nice to fix (naming, style, minor optimization)

## Controller Review

- [ ] **[HIGH]** Injects only Services (not Repositories)
- [ ] **[HIGH]** `@Valid` on all `@RequestBody` parameters
- [ ] **[HIGH]** Returns the domain type / `ResponseEntity<T>` — never hand-wraps in `ApiResponse`
- [ ] **[HIGH]** New public endpoint is a deliberate `SecurityConfig` addition, or carries `@RequirePermission`
- [ ] **[HIGH]** Action path is slash-style, never `:verb` (ADR-006)
- [ ] **[MEDIUM]** No business logic in controller method body
- [ ] **[MEDIUM]** `@Operation` for OpenAPI
- [ ] **[LOW]** Method name describes the action

## Service Review

- [ ] **[CRITICAL]** Read methods that map lazy associations have `@Transactional(readOnly = true)`
- [ ] **[HIGH]** Write methods have `@Transactional`
- [ ] **[HIGH]** Uses domain exceptions mapped through `ErrorType`
- [ ] **[HIGH]** Own-resource check for user-scoped operations (parent-owns-child, teacher-teaches-class)
- [ ] **[HIGH]** Uses a single `toResponse()`/`toView()` method (no raw field-by-field copy scattered across callers)
- [ ] **[HIGH]** Cross-module side effects go through an outbox row (`HashMap` payload), not a direct call into another module
- [ ] **[MEDIUM]** Method < 50 lines
- [ ] **[MEDIUM]** No `@Autowired` field injection (uses constructor via `@RequiredArgsConstructor`)
- [ ] **[LOW]** Method name is a clear verb

## Repository Review

- [ ] **[CRITICAL]** Every repository on a `TenantEntity` overrides `findById` with an explicit
      `@Query` — `EntityManager.find()` bypasses the Hibernate `@Filter`
      (`docs/COMMON_MISTAKES.md` #1)
- [ ] **[CRITICAL]** No string concatenation in JPQL/native queries
- [ ] **[HIGH]** Custom queries use `:paramName` (not formatted strings)
- [ ] **[MEDIUM]** No business logic in repository method
- [ ] **[LOW]** Query method name matches its behavior

## Entity Review

- [ ] **[HIGH]** Tenant-scoped entity extends `TenantEntity`
- [ ] **[HIGH]** `@Enumerated(EnumType.STRING)` on all enum columns
- [ ] **[HIGH]** Lazy fetch type on all `@ManyToOne` and `@OneToMany`
- [ ] **[MEDIUM]** No business logic in entity
- [ ] **[LOW]** Table name matches convention (plural snake_case)

## DTO Review

- [ ] **[HIGH]** Request records have validation annotations
- [ ] **[HIGH]** Validation messages use i18n keys present in both `messages_ar.properties` and `messages_en.properties`
- [ ] **[MEDIUM]** Response records do not expose password hashes or internal ids not needed by client
- [ ] **[LOW]** Uses record syntax for simple immutable DTOs

## Security Review

- [ ] **[CRITICAL]** No secrets hardcoded
- [ ] **[CRITICAL]** JWT validation complete (signature + expiry)
- [ ] **[HIGH]** WhatsApp webhook signature verified (`WebhookSignatureVerifier`) before processing
- [ ] **[HIGH]** No SQL string concatenation (injection risk)
- [ ] **[MEDIUM]** Error messages do not leak internal paths/SQL

## Test Review

- [ ] **[HIGH]** Both positive and negative test cases exist
- [ ] **[HIGH]** Test cleanup covers all created rows in the right FK order (children before parents)
- [ ] **[HIGH]** A new `TenantEntity` repository has a cross-tenant isolation test
- [ ] **[MEDIUM]** Test names follow `methodName_scenario_expectedBehavior`

## SchoolBridge-Specific Gotchas

- [ ] Enum values come from the real `public enum` source file — never guess or trust a stale doc
- [ ] `Map.of(...)` never used for an outbox/audit payload — a nullable field NPEs
      (`docs/COMMON_MISTAKES.md` #3)
- [ ] New FK to `users(id)`/`schools(id)` has `ON DELETE CASCADE`, or existing test teardown breaks
- [ ] A notification-channel stub reports failure, not success, when it hasn't sent anything
      (`docs/COMMON_MISTAKES.md` #15)
- [ ] `RestClient.builder()` has an explicit request factory (Windows JDK abort otherwise —
      `docs/COMMON_MISTAKES.md` #4)
