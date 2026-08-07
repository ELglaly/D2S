# SchoolBridge — Project Intelligence

> Global coding rules live in `~/.claude/rules/common/`. This file adds only
> project-specific context, conventions, and gotcha pointers.

## Identity

- **Project:** SchoolBridge — multi-tenant Spring Boot 3.3 / Java 21
- **Root:** `E:\D2L` | Package: `com.schoolbridge.api`
- **Platform:** Windows 11 / PowerShell

## Module Map

| Module         | Responsibility                                    |
|----------------|---------------------------------------------------|
| common         | Shared utilities, base entities, response wrapper |
| config         | Spring config, security, tenant context           |
| tenant         | School onboarding, tenant resolution              |
| identity       | Users, roles, JWT authentication                  |
| classes        | Classrooms, students, parent-child linking        |
| subjects       | Subject catalog (per-school)                      |
| grades         | Grade records                                     |
| announcements  | School announcements, targeting                   |
| integrations   | WhatsApp / external adapters                      |
| attendance     | Attendance records, reports                       |
| homework       | Homework items, recipients, reminders              |
| assistant      | AI assistant: conversation, tool-calling, RAG      |

Full system overview, tech stack, dependency direction, and folder-structure
conventions: `docs/ARCHITECTURE.md`. Domain terminology: `docs/DOMAIN_GLOSSARY.md`.

## Gated Build Order (Section 10)

tenant → identity → classes → announcements → integrations → attendance
→ **homework** ← current → fees → messaging → reporting → audit → hardening

Never skip a gate. Each module must reach green `mvn -B -ntp verify` before the next begins.
Use the **schoolbridge-new-module** skill when starting the next module.

## Build Commands

```shell
mvn spotless:apply                          # format (run before every commit)
mvn -B -ntp -DskipTests compile             # fast compile check
mvn -B -ntp verify                          # full build + tests
mvn -B -ntp test -Dtest=ClassName           # single test class
```

## Implementation Order (per module)

migration → entity → repo → DTO+mapper → service → controller → tests

## Critical Gotchas (full details in memory files)

- **Hibernate @Filter bypass** → every `TenantEntity` repo MUST override `findById` with `@Query`
  → `memory/feedback_hibernate_filter_findbyid_bypass.md`

- **UPAuthenticationToken trap** → never call `setAuthenticated(true)` after 3-arg constructor
  → `memory/feedback_springsecurity_uptoken_trap.md`

- **Outbox/audit Map.of NPE** → always build payloads with `HashMap`, never `Map.of()`
  → `memory/feedback_outbox_audit_mapof_npe.md`

- **RestClient on Windows JDK 23** → must use `SimpleClientHttpRequestFactory`
  → `memory/feedback_restclient_jdk_factory_windows.md`

- **AIP colon paths** → use `/actions/verb`, never `/:verb` (clients percent-encode `:`)
  → `memory/feedback_aip_colon_paths_dont_survive_clients.md`

- **ResponseBodyAdvice.supports()** → check Jackson converter type AND `com.schoolbridge.api` package
  → `memory/feedback_response_body_advice_exclusions.md`

- **ON DELETE CASCADE** → all FK refs to `users(id)` / `schools(id)` must cascade
  → `memory/feedback_device_token_fk_cascade.md`

A PostToolUse hook (`tools/hooks/check-known-gotchas.ps1`) advisory-checks
the first five of these automatically after every Edit/Write and surfaces a
warning if it spots the pattern — it doesn't block, verify it wasn't a false
positive before dismissing it. Full writeups + two more (Spotless import
timing, SpotBugs `\n` format-string): `docs/COMMON_MISTAKES.md`.

## Project Knowledge

- `docs/ARCHITECTURE.md` — system overview, tech stack, dependency direction, folder conventions
- `docs/DOMAIN_GLOSSARY.md` — business/domain terminology
- `docs/CHECKLISTS.md` — dev checklist, Definition of Done, code review / PR / release checklists
- `docs/COMMON_MISTAKES.md` — every gotcha above, expanded, with the fix
- `docs/adr/` — Architecture Decision Records for the *why* behind tenant isolation, RBAC, RAG, assistant tool architecture, routing style

## Project Skills & Agents

- Skills (`.claude/skills/`): `schoolbridge-new-module`, `schoolbridge-tenant-entity`,
  `schoolbridge-assistant-tool`, `schoolbridge-i18n-message` — invoke for their named task
  rather than re-deriving the convention from scratch.
- Agents (`.claude/agents/`): `tenant-isolation-auditor`, `i18n-parity-auditor` — read-only,
  run before closing a module gate or when a diff touches repositories / user-facing strings.
- Generic Spring Boot / Java / security / architecture work is already covered by the
  `everything-claude-code` plugin's global agents and skills — don't duplicate those
  project-locally; only add project-local assets for SchoolBridge-specific conventions.

## Hard Rules (summary — never violate)

1. Liquibase only; forward-only migrations
2. Spotless google-java-format, 2-space indent
3. i18n ar + en on ALL user-facing messages
4. Immutability — new objects, never mutate
5. Reuse existing services/components before adding new ones; no duplicate logic
6. Every module change ends green on `mvn -B -ntp verify` (Spotless + SpotBugs are hard gates, not just tests)
7. See gotchas above for traps 8-14
