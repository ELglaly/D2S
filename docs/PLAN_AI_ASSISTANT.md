# AI Assistant Module — Implementation Plan

**Status:** Planned — build gate after `reporting`, before `hardening`
**Date drafted:** 2026-06-09
**Module path:** `com.schoolbridge.api.assistant`

> 📄 **This is v1 (read-only Q&A).** For the action-capable assistant — where parents, teachers,
> and school admins can also *perform* every operation their role allows (mark attendance, grade
> homework, add students, etc.) via confirm-then-execute — see **[`PLAN_AI_ASSISTANT_V2.md`](./PLAN_AI_ASSISTANT_V2.md)**.
> v1 below remains the read-tool foundation v2 builds on.

---

## Overview

Add a natural-language assistant (`POST /api/v1/assistant/ask`) where parents and teachers send
free-text questions in Arabic or English and receive live, scoped data answers streamed via SSE.

Claude Haiku (Anthropic Java SDK) interprets the question, selects typed "tools" (thin Java
adapters over existing services), Spring executes them under the caller's tenant and role scope,
and the model composes a localized answer.

**The LLM never touches the database — only structured tool results.**

---

## Confirmed Decisions

| Decision | Choice |
|---|---|
| Module placement | After all planned modules: `fees → messaging → reporting → assistant → hardening` |
| DB migration | None for v1 — `audit_logs` + Redis only |
| PII to Anthropic | Send first names + data (natural answers: "Ahmed was absent today") |

---

## Full Gated Build Order (Updated)

```
tenant → identity → classes → announcements → integrations
→ attendance → homework (M9)
→ fees → messaging → reporting
→ assistant      ← this module
→ hardening
```

---

## Functional Requirements

- `POST /api/v1/assistant/ask` — authenticated, accepts free text, streams response via SSE
- Answer language mirrors the question language (ar / en); falls back to school default `Language`
- Parent sees only their linked children's data; teacher sees only their assigned classes
- Every ask + tool calls + outcome recorded in `audit_logs` via existing `AuditService`
- Identical question by same user same calendar day → served from Redis cache (zero LLM tokens)
- Feature ships dark (`enabled: false`); flipped per environment via config

---

## Example Questions

**Parent:**
- "Does my kid attend today?"
- "What grade did my child get on the last homework?"
- "Are there any homework assignments due today?"
- "Is there any important announcement I haven't acknowledged?"
- "How many days has my child been absent this month?"

**Teacher:**
- "How many students were absent in my class today?"
- "Which students haven't submitted last week's homework?"
- "What grades did I assign for the last math test?"
- "What classes do I teach?"

---

## Module Structure

```
com.schoolbridge.api.assistant/
  AssistantController.java            # POST /api/v1/assistant/ask (SseEmitter)
  AssistantService.java               # interface
  AssistantServiceImpl.java           # orchestration loop

  dto/
    AskRequest.java                   # { question: String, language: Optional<ar|en> }
    AssistantAnswer.java              # final answer + metadata (model, tokens, cached)
    AssistantChunk.java               # SSE chunk envelope (delta | toolStatus | done | error)

  llm/
    AnthropicClientConfig.java        # SDK client bean + AssistantProperties
    AssistantProperties.java          # @ConfigurationProperties("schoolbridge.assistant")
    LlmGateway.java                   # interface (decouples SDK for tests)
    AnthropicLlmGateway.java          # SDK impl, model claude-haiku-4-5-20251001
    SystemPrompt.java                 # builds system prompt (role, language, guardrails)

  tools/
    AssistantTool.java                # interface: name(), jsonSchema(), execute(args, ctx)
    ToolRegistry.java                 # registers only tools allowed for the current principal
    ToolContext.java                  # record(schoolId, principal, role, language)
    ToolResult.java                   # typed result serialized to JSON for the model
    impl/
      ChildAttendanceTodayTool.java
      ChildAbsenceCountTool.java
      ChildHomeworkDueTool.java
      ChildLatestGradeTool.java
      UnacknowledgedAnnouncementsTool.java
      ClassAttendanceTodayTool.java         # teacher
      ClassHomeworkOutstandingTool.java     # teacher
      ClassGradesForAssessmentTool.java     # teacher
      ListMyClassesTool.java                # teacher

  cache/
    AssistantCache.java               # Redis get/put, daily TTL, question normalization

  audit/
    AssistantAuditRecorder.java       # wraps AuditService with assistant-specific actions
```

---

## Tool Catalog

### Parent Tools

| Tool | Args | Backing Service | Scope Guard |
|---|---|---|---|
| `get_child_attendance_today` | `childName?` | `AttendanceService.history(studentId, today, today)` | `parentLinks.existsByParentUserIdAndStudentId` |
| `get_child_absence_count` | `childName?`, `month?` | `AttendanceService.history(...)` count ABSENT | same |
| `get_child_homework_due` | `childName?`, `dueDate?` | `HomeworkService.parentFeed(parentUserId, childId, page)` | feed is already parent-scoped |
| `get_child_latest_grade` | `childName?`, `subject?` | `GradeService.listByStudent(studentId)` newest | child-link guard |
| `get_unacknowledged_announcements` | none | `AnnouncementRecipientRepository` unack rows | `parentUserId`-scoped query |

### Teacher Tools

| Tool | Args | Backing Service | Scope Guard |
|---|---|---|---|
| `get_class_attendance_today` | `classId` / `className` | `AttendanceService.roster(classId, today)` | `schoolClasses.existsByIdAndTeacher` |
| `get_class_homework_outstanding` | `classId`, `period?` | `HomeworkService.list(classId)` + unacked recipients | `teacherTeaches` |
| `get_class_grades_for_assessment` | `classId`, `subject?`, `assessment?` | `GradeService.listByClass(classId)` | `teacherTeaches` |
| `list_my_classes` | none | `SchoolClassRepository` by teacher | self (principal) |

**Tool rules (all tools):**
- Resolve `schoolId` from `TenantContext.require()` — the LLM cannot supply a schoolId
- Re-run the same scope predicates as the controllers (`PermissionsHelper` equivalents)
- Scope denial → structured `ToolResult.denied(...)` fed back to the model; NOT an exception
- Fuzzy child/class name resolution happens in Java; ambiguity returns a clarification result

---

## Orchestration Flow

```
1. Validate AskRequest (non-blank, max 500 chars)
2. Resolve principal + role + language (detect from text, fall back to school Language)
3. Redis cache check:
     HIT  → stream cached answer → audit assistant.ask.cache_hit → return
     MISS → continue
4. Build ToolRegistry for the principal's role (parent OR teacher tools — never both)
5. Tool-use loop (max iterations = 4):
     a. Call LlmGateway with system prompt + tool catalog + messages
     b. Model returns tool_use → dispatch via ToolRegistry → append tool_result
        Emit SSE toolStatus chunk ("checking attendance…") for Flutter UX
     c. Model returns final text → stream delta chunks → emit done
6. Cache put (TTL = end of current day)
7. Audit: assistant.ask with HashMap metadata
   (question hash, language, tools invoked, iterations, token usage, cached=false, latencyMs)
   MUST use HashMap — never Map.of() (outbox/audit NPE gotcha)
```

---

## Streaming & ResponseBodyAdvice

The endpoint returns `SseEmitter` (`text/event-stream`).
`ApiResponseBodyAdvice.supports()` only triggers for `MappingJackson2HttpMessageConverter`,
so SSE responses are **not wrapped** — no change needed to the advice.

Errors mid-stream: emit a terminal SSE `error` chunk and complete the emitter; log full
context server-side. The global `@RestControllerAdvice` cannot intercept after headers are flushed.

---

## Configuration

```yaml
schoolbridge:
  assistant:
    enabled: ${ASSISTANT_ENABLED:false}       # ships dark
    api-key: ${ANTHROPIC_API_KEY:}            # env-only; validated at startup when enabled=true
    model: claude-haiku-4-5-20251001
    max-tool-iterations: 4
    request-timeout: 30s
    cache-ttl: PT24H
    max-question-length: 500
```

**Security:** `ANTHROPIC_API_KEY` is env-only, never in `application.yml` defaults, never logged,
never in audit metadata. Validated present at startup when `enabled=true`.

### pom.xml dependency to add
```xml
<dependency>
  <groupId>com.anthropic</groupId>
  <artifactId>anthropic-java</artifactId>
  <version>${anthropic.version}</version>
</dependency>
```
Add `<anthropic.version>` property pinned to a fixed release.

---

## Implementation Phases

### Phase 1 — Tool Layer (no LLM, fully testable)
1. `AssistantProperties` + config block in `application.yml`
2. `AssistantTool` interface, `ToolContext`, `ToolResult`, `ToolRegistry`
3. All tool implementations wrapping existing services + scope guards
4. Unit tests per tool: happy path, denied cross-child/cross-class, ambiguous name

### Phase 2 — LLM Gateway & Orchestration
5. Add Anthropic dependency to `pom.xml`; `AnthropicClientConfig` bean (gated on `enabled`)
6. `LlmGateway` interface + `AnthropicLlmGateway` + `SystemPrompt` builder
7. `AssistantServiceImpl` tool-use loop with iteration cap
8. Tests with stubbed `LlmGateway`: single-tool, multi-tool, max-iteration cutoff,
   tool-denied path, model-final-without-tool path

### Phase 3 — Endpoint, Streaming, Cache, Audit
9. `AssistantCache` (Redis): daily TTL + question normalization (trim/lowercase/collapse spaces)
10. `AssistantAuditRecorder` wrapping `AuditService` (HashMap metadata)
11. `AssistantController` returning `SseEmitter`; wire cache → service → audit
    Register route in `SecurityConfig` as authenticated
12. i18n strings for fixed messages (errors, "no access", "I couldn't find that child")
    in `messages_ar.properties` / `messages_en.properties`
13. Integration test: SSE frames not double-wrapped, cache-hit short-circuit,
    audit row written, tenant isolation (two-school test), rate-limit 429

### Phase 4 — Hardening
14. Per-user rate limiting on `/assistant/ask` (reuse Redis pattern from `LoginRateLimiter`)
15. Resilience4j circuit breaker + timeout around Anthropic calls; graceful localized fallback
16. Micrometer counters/timers: asks, cache-hit ratio, token usage, tool-call distribution, LLM latency
17. Optional `012-assistant.sql` analytics table only if product requests (not planned for v1)

Each phase is independently mergeable; phases 1–3 ship behind `enabled=false`.

---

## Key Files to Integrate With
/
**Services the tools wrap:**
- `attendance/AttendanceService.java`
- `homework/HomeworkService.java`
- `grades/GradeService.java`
- `announcements/service/AnnouncementService.java`
- `classes/service/ParentChildrenService.java`

**Patterns to mirror:**
- `common/security/PermissionsHelper.java` — scope guard predicates
- `homework/HomeworkController.java` — principal resolution pattern
- `common/audit/AuditService.java` — audit method signature
- `common/web/ApiResponseBodyAdvice.java` — SSE non-wrapping verification
- `common/security/SecurityConfig.java` — route registration

---

## Risk Assessment

| Risk | Severity | Mitigation |
|---|---|---|
| Scope leakage via model-supplied IDs | **CRITICAL** | `schoolId` always from `TenantContext`, never from LLM args; `parentLinkedTo` / `teacherTeaches` re-checked in Java for every tool call; mandatory cross-tenant tests |
| Prompt injection | High | LLM has no DB access; tools enforce scope server-side regardless of input content |
| PII (names/grades) sent to Anthropic | High | Acceptable per confirmed decision; send minimal fields only (no national IDs, no AES-encrypted attrs); confirm DPA before prod |
| Anthropic SDK HTTP on Windows JDK | Medium | Smoke-test SDK call early in Phase 2; configure SDK HTTP client explicitly if it aborts (mirrors `SimpleClientHttpRequestFactory` fix) |
| Cost / latency runaway | Medium | Haiku model + iteration cap + Redis cache + per-user rate limit + circuit breaker |
| SSE mid-stream errors | Medium | Terminal `error` SSE chunk + emitter completion + server-side logging |
| Secret exposure | Low | API key env-only, startup validation, excluded from all logs and audit payloads |

---

## Test Strategy

| Layer | What to cover |
|---|---|
| Unit | Each tool (happy path, denied, ambiguous); `ToolRegistry` role filtering; `SystemPrompt` language; cache key normalization; orchestration loop with stubbed `LlmGateway` |
| Integration | SSE endpoint: no `ApiResponse` wrapping, cache-hit path, audit row, rate-limit 429, two-school tenant isolation, parent-vs-teacher tool availability |
| E2E (opt-in) | One RestAssured flow per persona against real Anthropic API, gated on env flag, excluded from CI default — mirrors WireMock pattern used for WhatsApp |

Target: **80%+ coverage** on the module before moving to `hardening`.

---

## Success Criteria

- [ ] Parent receives correct, localized answers for all 5 example questions
- [ ] Teacher receives correct, localized answers for all 4 example questions
- [ ] A parent cannot retrieve another family's data (test-proven, two-school fixture)
- [ ] LLM never receives SQL or DB handles; only the tool catalog + scoped results
- [ ] Every ask produces exactly one `audit_logs` entry (cache hits included)
- [ ] Identical question by same user same day → Redis hit, zero LLM tokens consumed
- [ ] Responses stream over SSE and are not wrapped by `ApiResponseBodyAdvice`
- [ ] Feature ships dark and `mvn -B -ntp verify` is green with 80%+ coverage
