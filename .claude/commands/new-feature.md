---
description: Scaffold a new SchoolBridge feature — entity, request/response records, service, controller, Liquibase migration, integration tests.
argument-hint: <module>/<feature> e.g. "homework/attachment-link" or "attendance/monthly-report"
---

Scaffold a complete new SchoolBridge feature.

Feature target: $ARGUMENTS  (format: `<module>/<feature-name>`)

Follow these steps in order:

1. **Read context first**:
   - `.claude/CLAUDE.md`
   - `.claude/rules/java/schoolbridge-modules.md` — confirm the target module exists; if it doesn't, stop and ask
   - `.claude/rules/java/schoolbridge-domain-model.md` — reuse existing enums, don't invent new values
   - `.claude/rules/java/schoolbridge-api-map.md` — confirm the new endpoints don't collide
   - `docs/COMMON_MISTAKES.md`

2. **Determine cross-module touches**:
   - Does this write need to trigger a side effect in another module (notification, audit)? → plan
     an outbox row + `integrations` consumer
   - Does this read need data owned by another module? → call that module's service directly
     (constructor-injected)

3. **Create / extend the entity** in `com.schoolbridge.api.<module>`:
   - Explicit hand-written constructor + getters, **not Lombok** — Lombok is a declared dependency
     but unused throughout `src/main/java`; match the existing style (see `HomeworkItem` for the
     canonical example: a protected no-arg ctor for JPA, a public ctor with all fields, explicit
     `get*()` methods, and rich domain methods like `publish()`/`archive()` instead of setters)
   - `@Entity @Table(name = "<table>")`
   - Extend `TenantEntity` if the data is school-scoped (it almost always is)
   - All enum fields: `@Enumerated(EnumType.STRING)`
   - Money: `BigDecimal`/`NUMERIC`; time: `Instant`

4. **Add or reuse enums** at the module root:
   - First check `schoolbridge-domain-model.md` and the real `public enum` files; never duplicate an
     existing enum

5. **Create request DTOs (Java records)** in `com.schoolbridge.api.<module>.dto`:
   - `Create<Feature>Request`, `Update<Feature>Request` — with Bean Validation annotations
   - Validation messages use i18n keys; add the key to **both** `messages_ar.properties` and
     `messages_en.properties`

6. **Create response records** in `com.schoolbridge.api.<module>.dto`:
   - `<Feature>Response` — what the controller returns (the envelope wrapping happens automatically
     via `ApiResponseBodyAdvice`, don't wrap it yourself)

7. **Create the repository** in `com.schoolbridge.api.<module>`:
   - `extends JpaRepository<Entity, UUID>`
   - If the entity extends `TenantEntity`, **override `findById` with an explicit `@Query`**
     (`docs/COMMON_MISTAKES.md` #1) — this is not optional

8. **Create the service** — interface + `*Impl` in `com.schoolbridge.api.<module>`:
   - `@Service @RequiredArgsConstructor`
   - `@Transactional(readOnly = true)` on read methods that traverse lazy fields; `@Transactional`
     on write methods
   - Use domain-specific exceptions mapped through `ErrorType`
   - Map entity → record manually (no MapStruct) — keep it explicit
   - For a cross-module side effect: write an outbox row (`OutboxEventRecorder`, `HashMap` payload)
     in the same transaction — never `Map.of(...)`

9. **Create the controller** in `com.schoolbridge.api.<module>`:
   - `@RestController @RequestMapping(ApiConstants.API_V1 + "/<base>") @RequiredArgsConstructor`
   - `@RequirePermission(...)` on mutating endpoints; row-ownership via `@PreAuthorize` where needed
   - Slash-style action paths only — never `/resource:action` (ADR-006)
   - A genuinely public endpoint is an explicit, deliberate entry in `SecurityConfig`, never a
     fallthrough

10. **Create Liquibase migration** in `src/main/resources/db/changelog/`:
    - Name: next sequential number (check `db.changelog-master.yaml`'s tail) + `-<short-desc>.sql`
    - `--liquibase formatted sql`, `--changeset schoolbridge:NNN-<desc>`, `--comment:`, `--rollback`
    - `id UUID PRIMARY KEY`, `school_id UUID NOT NULL REFERENCES schools(id) ON DELETE CASCADE` if
      tenant-scoped, `TIMESTAMPTZ` timestamps
    - Add a new `- include: { file: NNN-....sql, relativeToChangelogFile: true }` entry at the end
      of `db.changelog-master.yaml`

11. **Add i18n message keys**:
    - Add validation/response keys to `messages_en.properties` **and** `messages_ar.properties` —
      i18n parity is a hard requirement, not an afterthought

12. **Create integration tests** in `src/test/java/com/schoolbridge/api/<module>/`:
    - Extend `AbstractIntegrationTest`, use REST Assured (`given()/when()/then()`) matching the
      style of a sibling test in the same module
    - If a new `TenantEntity` repository was added: write a `*RepositoryIsolationTest`
    - Cover: happy path, 401 unauthenticated, 403 forbidden (if permission-gated), 404 not found,
      400/422 validation error

13. **Update docs**:
    - Add the new endpoints to `.claude/rules/java/schoolbridge-api-map.md`
    - If a new entity/enum was added, update `.claude/rules/java/schoolbridge-domain-model.md`

14. **Verify**:
    ```bash
    mvnw.cmd -DskipTests compile
    mvnw.cmd spotless:apply
    mvnw.cmd test -Dtest=TenantEntityArchUnitTest
    mvnw.cmd test -Dtest=<NewFeatureTest>
    ```
    All must be green before reporting done.
