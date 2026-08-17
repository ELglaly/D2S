---
name: architecture-review
description: Macro-level architecture review for SchoolBridge. Checks tenant isolation, layer violations, cross-module conventions, and adherence to Controller→Service→Repository pattern.
metadata:
  version: "2.0.0"
  domain: architecture
  triggers: architecture review, layer violation, package structure, design review, tenant isolation
  role: reviewer
  scope: architecture
  output-format: checklist
---

# Architecture Review Skill (SchoolBridge)

## Layer Boundary Checklist

### Controller Layer
- [ ] Only injects Service classes (no Repository injection)
- [ ] No business logic (delegates everything to service)
- [ ] Uses `@Valid` on all `@RequestBody` params
- [ ] Returns the domain response type / `ResponseEntity<T>` — `ApiResponseBodyAdvice` wraps it, the
      controller never hand-wraps
- [ ] Uses `@RequirePermission` for permission-gated access (not manual role checks)
- [ ] No `@Transactional` annotations (belongs in service)
- [ ] No direct entity usage in method signatures (uses DTOs/records only)

### Service Layer
- [ ] Interface + `*Impl` split (`HomeworkService` / `HomeworkServiceImpl`)
- [ ] Only injects Repository and other Service classes
- [ ] All state-changing methods have `@Transactional`
- [ ] All read methods with lazy-loaded associations have `@Transactional(readOnly = true)`
- [ ] Uses domain-specific exceptions mapped through `ErrorType`
- [ ] Uses a single `toResponse()`/`toView()` helper per entity (no repeated inline mapping)
- [ ] No HTTP/servlet concepts (`HttpServletRequest`, `HttpStatus`, `ResponseEntity`)
- [ ] Business invariants enforced here (not in controller or repository)

### Repository Layer
- [ ] Only Spring Data JPA interfaces (no business logic)
- [ ] **Every repository on a `TenantEntity` overrides `findById` with an explicit `@Query`** — the
      Hibernate `@Filter` does not apply to `EntityManager.find()` (`docs/COMMON_MISTAKES.md` #1).
      This is the single most important check in this section.
- [ ] Custom queries use JPQL or native SQL with named parameters — no string concatenation
- [ ] `Pageable` used with explicit sort direction where ORDER BY matters

### DTO Layer (`dto/`)
- [ ] Separate Request and Response records (not shared)
- [ ] Validation annotations only on Request records
- [ ] Response records do not contain password hashes or sensitive internal fields
- [ ] Uses i18n message keys (present in both `messages_ar.properties`/`messages_en.properties`),
      not hardcoded strings, in `@NotBlank(message = ...)` etc.

### Entity Layer
- [ ] Entities never returned from controllers
- [ ] Tenant-scoped entities extend `TenantEntity`
- [ ] `@ManyToOne`/`@OneToMany` are `FetchType.LAZY` unless proven necessary
- [ ] Enums stored as `@Enumerated(EnumType.STRING)`
- [ ] No business logic in entities (use service layer)

### Module & Cross-Module Conventions
- [ ] Change respects the dependency direction in `.claude/rules/java/schoolbridge-modules.md`
      (`tenant → identity → classes → announcements → integrations → attendance → homework →
      attachments → notifications → assistant`) — no backward pointer
- [ ] Cross-module side effects go through an outbox row in the same transaction
      (`OutboxEventRecorder`, `HashMap` payload — never `Map.of(...)`), consumed asynchronously by
      `integrations`
- [ ] `assistant/tools/*` call the target module's **service**, never its repository (ADR-005)
- [ ] There is no compiler-enforced module boundary here (no Spring Modulith) — don't invent
      `package-info.java`/`@ApplicationModule` conventions that don't exist in this codebase

### Security Layer
- [ ] JWT validation happens once, centrally (not repeated per-controller)
- [ ] `SecurityConfig` is the single source of truth for public paths
- [ ] Custom entry point and access-denied handler return JSON through the same `ApiResponse` shape

## Cross-Cutting Concerns

### Error Handling
- [ ] A global exception handler maps every custom exception to an `ErrorType`
- [ ] No `try/catch` in controllers (let the global handler do it)

### i18n
- [ ] `messages_ar.properties` and `messages_en.properties` both have every new key

### Package Dependency Graph (should be acyclic, within a module)
```
Controller → Service (+ Impl) → Repository → Entity
                  ↓
                 dto/
```
No arrows should point upward (e.g., a repository must NOT import a controller class).
