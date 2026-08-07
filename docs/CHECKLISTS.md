# SchoolBridge — Checklists

> What "done" actually means at each stage. `mvn -B -ntp verify` enforces
> Spotless (google-java-format) and SpotBugs (effort=Max, threshold=Medium)
> as hard gates — a build can be green on tests and still fail here. There
> is **no** JaCoCo coverage gate; "80%+ coverage" below is a quality bar the
> team holds itself to, not something CI blocks on.

## Development checklist (before you consider a change finished)

- [ ] Follows the module's existing package-by-feature layout (entity/repo/
      service/controller at module root, `dto/` sub-package)
- [ ] New `TenantEntity` repo overrides `findById` with an explicit `@Query`
      (`docs/COMMON_MISTAKES.md` — Hibernate `@Filter` bypass)
- [ ] New user-facing message added to **both** `messages_en.properties` and
      `messages_ar.properties` (exact key parity)
- [ ] No mutation of existing objects — new-object-return pattern throughout
- [ ] Outbox/audit payloads built with `HashMap`, never `Map.of(...)`
- [ ] New endpoints use `@RequirePermission` (or an explicit, documented
      reason why not) and slash-style paths (`/actions/verb`, never `/:verb`)
- [ ] `mvn spotless:apply` run before final review
- [ ] `mvn -B -ntp -DskipTests compile` clean
- [ ] Liquibase changelog is forward-only (no edits to already-applied
      changesets) and registered in `db.changelog-master.yaml`

## Definition of Done

A change is Done when, in addition to the development checklist:

- [ ] `mvn -B -ntp verify` is green (tests + Spotless + SpotBugs)
- [ ] Unit tests cover the new/changed logic; integration tests cover new
      endpoints or repository queries with non-trivial predicates
- [ ] OpenAPI docs (`docs/api/openapi.{json,yaml}`) regenerated/updated if
      the controller surface changed
- [ ] No new `TODO` without a linked follow-up (see `docs/COMMON_MISTAKES.md`
      L1 precedent — audit gaps left as silent TODOs get lost)
- [ ] If the change touches a module gate boundary (see gate order in
      `.claude/CLAUDE.md`), the *whole* module reaches green `verify`, not
      just the new files

## Code review checklist

- [ ] Tenant isolation: any new repo method that could leak cross-tenant
      rows outside an active `@Transactional` context?
- [ ] Authorization: permission on the controller method matches the
      permission the assistant `Tool` (if any) declares for the same action
- [ ] Secrets: no hardcoded credentials, no new fallback defaults in
      `application*.yml` for anything sensitive
- [ ] Error handling: exceptions caught with purpose, not swallowed;
      user-facing messages localized, server logs carry context
- [ ] Immutability: no in-place mutation of entities/DTOs outside JPA-managed
      entity setters used by the persistence layer itself
- [ ] File size sane (guideline 200–400 lines, 800 max) and methods under
      ~50 lines — flag, don't block, if a genuinely cohesive method runs long
- [ ] i18n key parity holds (`en`/`ar`) for anything touched
- [ ] No `System.out`/`printStackTrace`, no empty catch blocks

## Pull request checklist

- [ ] Title follows `type: description` (`feat`, `fix`, `refactor`, `docs`,
      `test`, `chore`, `perf`, `ci`)
- [ ] Description explains *why*, not just what (the diff shows what)
- [ ] Linked to the relevant module gate / plan doc if one exists in `docs/`
- [ ] Test plan included — what was run, what a reviewer should verify
      manually if anything isn't covered by automated tests
- [ ] `git diff main...HEAD` reviewed by the author before requesting review
      (not just the latest commit)
- [ ] No unrelated formatting-only diffs mixed into a functional change

## Release checklist

- [ ] Previous module's gate is green (`mvn -B -ntp verify`) before starting
      work that depends on it
- [ ] `application-prod.yml` has no-default env vars for every secret
      (mirror the pattern already used for DB/crypto keys)
- [ ] Liquibase changelog applies cleanly against a fresh database
      (no manual out-of-band schema changes)
- [ ] Actuator health endpoint access is `when_authorized` in prod, Swagger
      disabled in prod
- [ ] Structured logs (logstash encoder) don't leak PII — check anything
      touching encrypted/blind-indexed fields
