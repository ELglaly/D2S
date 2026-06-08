# Flutter App Planning Prompt — SchoolBridge

> **What this file is.** A single, self-contained prompt you paste into a capable AI (Claude Opus, GPT, etc.) to make it produce a *comprehensive implementation plan* for a Flutter app that consumes the SchoolBridge backend and adopts the **si-education** visual theme.
>
> **How to use it.**
> 1. Give the AI access to the two source trees (or paste the relevant files):
>    - CODEX**Backend:** `E:\D2L` (Spring Boot API — read `docs/IMPLEMENTATION_PLAN.md`, `SchoolBridge_SRS_v1.0.md`, and `src/main/java/com/schoolbridge/api/**`).
>    - **Theme:** `C:\Users\sheri\Downloads\Compressed\si-education-1.0.0\si-education-1.0.0` (Next.js + Tailwind v4 template — read `src/app/globals.css`, `src/app/layout.tsx`, `src/app/components/**`).
> 2. Copy everything below the line marked **“=== PROMPT STARTS HERE ===”** into the AI.
> 3. The AI returns a plan as a markdown document. Review, then approve module-by-module before any code is written.
>
> The prompt already embeds a verified snapshot of the backend API surface and the theme tokens (Appendices A–C) so the AI has ground truth even without repo access — but it is instructed to re-verify against the live source.

---

=== PROMPT STARTS HERE ===

## Role

You are a **principal Flutter architect** with deep experience shipping production, multi-tenant, bilingual (Arabic/English, RTL) mobile apps against Spring Boot REST backends. You write plans that a mid-level Flutter team can execute without re-deriving decisions.

## Mission

Produce a **comprehensive, build-ready implementation plan** (a markdown document — *not code yet*) for a **Flutter mobile app** that is the **primary client** for the **SchoolBridge** backend, adopting the **si-education** design language. The plan must be detailed enough that each subsequent module can be coded directly from it.

**Hard constraint on your process:** Before writing the plan, (1) read the actual backend source to confirm every endpoint, request/response shape, header, and auth rule — do **not** trust assumptions; the embedded Appendix A is a snapshot and may have drifted. (2) Research current best-practice Flutter packages and patterns (prefer battle-tested libraries over hand-rolled code; cite the package + why). (3) Surface open questions and **wait for confirmation** before proposing final module sequencing.

---

## 1. Context you are working from

### 1.1 The product (from `SchoolBridge_SRS_v1.0.md`)

SchoolBridge is a **WhatsApp-first school↔parent communication SaaS** for private schools and tutoring centres in Egypt/MENA. It is **multi-tenant** (each school = one tenant, isolated data). MVP workflows: **announcements, homework, attendance notifications, fee status & reminders, and direct teacher↔parent messaging.**

User classes:
- **Super Admin** — internal SchoolBridge staff (provisioning, billing). *Out of scope for the mobile app; assume an internal web tool.*
- **School Admin** — owns a school tenant: users, classes, fees, announcements, reports.
- **Teacher** — posts homework, takes attendance, sends class messages, replies to parents.
- **Parent** — receives notifications, views child's homework/attendance/fees, acknowledges, replies. Low digital literacy assumed.

**Important reconciliation you must honour:** The SRS describes parents interacting *primarily via WhatsApp* and staff via a *web dashboard*. The current product direction is different: **the Flutter app is the primary client and must serve School Admin, Teacher, and Parent roles natively.** WhatsApp remains a parallel notification channel (the backend keeps sending WhatsApp messages), but the app is now the first-class surface. Plan a **single app with role-aware navigation and feature gating** (one binary, three role experiences), not three apps — unless you make a strong, explicitly-justified case otherwise and flag it as an open question.

### 1.2 The backend (authoritative facts; verify against source)

- **Stack:** Spring Boot 3.3.5 / Java 21, PostgreSQL 16, Redis, RabbitMQ, modular monolith. Status: **M1–M8 complete and green; M9 (Homework) in progress**; Fees, Messaging, Reporting, Audit, Hardening planned (see `docs/IMPLEMENTATION_PLAN.md` §10).
- **Base path:** `/api/v1`. **Webhooks** (WhatsApp) live outside `/api`.
- **Response envelope:** responses are wrapped by a global `ApiResponseBodyAdvice`; paginated lists use a `PageResponse<T>` shape. **Confirm the exact envelope** (fields like `data`, `success`, `error`, and the page metadata) by reading `common/web/ApiResponseBodyAdvice.java` and `common/web/PageResponse.java` — your Dio/Retrofit deserialization depends on getting this exactly right.
- **Errors:** RFC 7807 `application/problem+json` ProblemDetail with `type, title, status, detail, instance, traceId, errors[]` (per-field validation). Your error layer must parse this, surface `errors[]` to forms, and show `traceId` in diagnostics.
- **Auth — two distinct schemes (read `identity/auth/**`):**
  - **Staff (Admin/Teacher):** `POST /api/v1/auth/login` (email + password) → **RS256 access JWT (~15 min) + opaque refresh token (~30 d, rotated)**. `POST /api/v1/auth/refresh` rotates; `POST /api/v1/auth/logout` revokes. JWT claims include `sub, schoolId, role`.
  - **Parents:** `POST /api/v1/parents/auth/request-otp` → OTP via WhatsApp → `POST /api/v1/parents/auth/verify-otp` → **opaque session token** (bound to `parentUserId + activeSchoolId`). `POST /api/v1/parents/auth/logout`. Multi-school parents may need a school-selection step.
- **Multi-tenancy:** the tenant (`schoolId`) is derived **server-side** from the authenticated principal/JWT — the client generally does **not** send a tenant header for staff. **Verify** whether any endpoint expects an explicit school header (check `common/security/TenantBindingFilter.java`); if parents are multi-school, confirm how the active school is selected/sent.
- **Push notifications:** device registration exists — `POST /api/v1/devices/register` and `DELETE /api/v1/devices/{deviceId}` (read `identity/device/DeviceController.java`). This is the FCM/APNs token sink. Plan FCM end-to-end on the client.
- **Idempotency:** writes accept an `Idempotency-Key` header (read `common/idempotency/**`). Your API client must generate and attach a UUID idempotency key on POST/PUT/PATCH that create or mutate, and reuse it across retries of the same logical request.
- **Request tracing:** `X-Request-Id` header is read by `RequestIdFilter`; send a per-request id and log the response `traceId` for support.
- **i18n:** backend resolves messages by `Accept-Language` (`ar` / `en`). Send it. **Arabic requires full RTL** in the app.
- **Encryption/PII:** names, phones, message bodies are encrypted at rest server-side — irrelevant to transport, but treat all such data as sensitive in local caches/logs.

### 1.3 Backend gotchas that change client design (do not rediscover these)

1. **Action-path encoding trap.** Most controllers use **slash-style** action verbs (`POST /attendance/mark`, `/announcements/{id}/recall`, `/announcements/{id}/acknowledge`, `/attendance/{id}/parent-response`). **However** at least one endpoint still uses an AIP **colon** verb: `POST /api/v1/students:bulk-import`. Generic HTTP clients percent-encode `:` → `%3A`, which breaks Spring routing. **Your API client must send the literal `:` un-encoded for any colon-style path**, or you must coordinate a backend move to slash-style. Call this out explicitly and centralize URL building so it's handled in one place.
2. **Pagination + envelope** are non-standard (custom `PageResponse` + body advice). Build the networking layer around the *actual* shapes, not Spring Data defaults.
3. **Two token lifecycles in one app.** Staff (JWT+refresh) and Parent (opaque) auth differ. Your auth/session layer must abstract “current credential” so the rest of the app is role-agnostic, while the token-refresh interceptor branches correctly (only staff tokens refresh; parent tokens re-OTP).

### 1.4 The theme (`si-education`, Tailwind v4 + Next.js)

It is a **web** template — you are **porting its design language to Flutter**, not its code. Authoritative tokens are in `src/app/globals.css` (`@theme` block) and `src/app/layout.tsx`. Verified snapshot (re-check the file):

- **Colors:** `primary #611f69` (deep aubergine/purple), `cream #fcf5ef` (warm off-white background), `success #6b9f36` (green), `orange #f9cd92` (soft peach accent). Default borders ≈ `gray-200 #e5e7eb`.
- **Typography:** **Inter** (Google Fonts), large bold headings (h1 ~text-5xl→7xl, h2 ~text-4xl→5xl), comfortable body.
- **Shape & spacing:** rounded-md cards, subtle 1px borders, generous section padding (`py-14`), centered max-width container with horizontal padding.
- **Elevation:** soft shadow `mentor-shadow: 0 4px 20px rgba(110,127,185,0.1)`.
- **Light/visual tone:** clean, friendly, education-marketing aesthetic; cream surfaces with purple primary actions and green for positive/success states.
- **Component vocabulary to translate:** Hero, Courses cards, Mentor cards, Testimonial, Newsletter CTA, Companies strip, full Auth set (SignIn/SignUp/ForgotPassword/ResetPassword/MagicLink/Social), Breadcrumb, Loader/PreLoader, Skeleton, NotFound. Map each to a Flutter widget equivalent in the design system.

Your plan must include a **token-translation table** (Tailwind/CSS value → Flutter `ColorScheme`/`ThemeData`/`TextTheme` entry) and define a **Material 3 theme seeded from `#611f69`** with the cream surface, success/peach as semantic/tertiary colors, Inter via `google_fonts`, full **light + dark** schemes, and a fully mirrored **RTL** theme.

---

## 2. What the plan you produce MUST contain

Produce a single markdown document with these sections. Be specific and decisive; justify non-obvious choices in one line each.

1. **Executive summary & scope** — what the app is, which roles, MVP vs later, platform targets (iOS/Android; tablet support?), minimum OS versions (SRS says Android 8+, iOS 13+).
2. **Backend-readiness audit** — a checklist of what the API already provides vs. what the Flutter app will need that may be missing. Explicitly check for: an OpenAPI/Swagger spec to generate the client from (springdoc); a consolidated **parent dashboard / “my children” aggregate** endpoint (or document the N calls needed); **file upload** endpoints for homework/announcement attachments + pre-signed URL download; teacher/admin **profile/me** endpoint; **fees** and **messaging** endpoints (may not exist yet — flag as backend dependencies). Output a table: *Need → Exists? → Endpoint or Gap → Owner (backend/frontend) → Blocking which app module.*
3. **Architecture** — pick and justify: state management (e.g., Riverpod vs Bloc — recommend one), project layering (feature-first clean architecture: `presentation / application / domain / data`), folder structure, dependency injection, routing (e.g., `go_router` with role-based redirects/guards), and immutable models (`freezed`). Honor the house rules in §3.
4. **Package selection** — a table of chosen packages with version + rationale + alternative considered. Cover: networking (dio + retrofit or chopper), JSON/codegen (`freezed` + `json_serializable` or OpenAPI generator), DI (`get_it`/`injectable` or Riverpod providers), secure storage (`flutter_secure_storage` for tokens), local cache/offline (`drift`/`isar`/`hive` — pick one, justify), push (`firebase_messaging` + `flutter_local_notifications`), i18n (`flutter_localizations` + `intl`/ARB or `slang`), forms/validation, image handling, file picker, charts (for reports), date/timezone (`timezone` — schools have per-tenant tz).
5. **Networking & data layer** — Dio setup; interceptors for: auth header injection (branching staff JWT vs parent token), **token refresh** (queue requests during refresh; staff only), idempotency-key generation, `X-Request-Id`, `Accept-Language`, the **colon-path** handling, RFC 7807 error mapping → typed `Failure`s, the `PageResponse`/envelope unwrapping, retry/backoff, logging (PII-safe). Define the repository pattern per feature.
6. **Authentication & session** — full flows with sequence diagrams (text/mermaid) for: staff login → token store → silent refresh → logout; parent request-OTP → verify → session → multi-school selection → logout. Secure token storage, biometric unlock (optional), session expiry UX, “logged out everywhere” handling.
7. **Feature breakdown — one subsection per module**, each mapped to backend endpoints (Appendix A) and SRS FRs, with: screens/widgets, state, API calls, role visibility, empty/loading/error/offline states, and acceptance criteria. Cover at minimum:
   - **Auth & onboarding** (role-aware landing).
   - **Schools/settings** (admin; tenant settings, quiet hours, reminder times).
   - **Users** (admin; list/create/suspend; CSV bulk import UX → `students:bulk-import`).
   - **Classes / Students / Enrollment / Teacher assignment / Parent links** (admin; teacher read-own).
   - **Announcements** (compose w/ scope picker + attachment + language + scheduling + required-ack; delivery/recipients view; recall; parent acknowledge).
   - **Homework** (teacher post/edit/publish; parent feed by child; acknowledge; reminders).
   - **Attendance** (teacher daily roster, one-tap mark, mark-all-present, bulk flip; parent history + quick parent-response; the 5-min alert is server-side).
   - **Fees** (admin define items, record payments, ledger; parent statement view) — *flag if endpoints don't exist yet.*
   - **Messaging** (teacher↔parent threads, quiet hours, report) — *flag if not built yet.*
   - **Reports/dashboard** (admin overview: attendance rate, overdue fees, announcements sent, message volume; charts).
   - **Notifications** (FCM registration via `/devices/register`, deep-linking a push into the right screen, in-app notification center).
   - **Profile/settings** (language toggle AR/EN, theme, logout).
8. **Design system** — the token-translation table; Material 3 theme (light/dark); typography scale with Inter; component library (buttons, cards mirroring si-education Course/Mentor cards, inputs, app bars, bottom nav/rail, skeletons/loaders, empty states, snackbars/toasts, dialogs); spacing/elevation/radius constants; iconography; **RTL mirroring** rules and Arabic typography. Include 2–3 key screens as ASCII/wireframe sketches.
9. **Internationalization & RTL** — ARB structure, key naming, plural/gender, number/date/currency localization per school locale/timezone, `Directionality` handling, mirrored layouts, font fallback for Arabic.
10. **Offline, caching & sync** — what's cached (rosters, feeds), read-through strategy, optimistic updates (e.g., attendance marking), conflict handling, idempotency-key reuse on retry, connectivity UX (3G-tolerant per SRS).
11. **Push notifications** — FCM/APNs setup, token lifecycle tied to login/logout (`/devices/register` + `DELETE /devices/{id}`), foreground/background/terminated handling, channels per notification type, deep links, permission UX.
12. **Security** — secure token storage, no PII in logs, certificate pinning (consider), biometric gate, screenshot protection on sensitive screens, jailbreak/root awareness (optional), RBAC enforced server-side (client gating is advisory — state this), secrets handling (no secrets in the binary).
13. **Testing strategy** — unit (domain/repos with mocked Dio), widget tests, golden tests for the design system (light/dark/RTL), integration tests for critical flows (login, attendance mark, announcement send), contract alignment to OpenAPI, target **≥80% coverage**. Name the tooling (`mocktail`, `golden_toolkit`/`alchemist`, `patrol`/`integration_test`).
14. **CI/CD & tooling** — analyzer + lints (`very_good_analysis` or `flutter_lints`), format/check, codegen step, test+coverage gate, build flavors (dev/staging/prod with per-env base URLs), code signing, distribution (Firebase App Distribution/TestFlight), release versioning.
15. **Environment & config** — flavors, base URLs, `--dart-define`/`.env` strategy, Firebase config per flavor, backend `local`/`prod` parity.
16. **Milestone roadmap** — phased delivery that **mirrors the backend's strict module-by-module gated cadence** (mini-plan → confirm → build → green → next). Each milestone: scope, dependencies (incl. backend modules that must exist first — e.g., Messaging/Fees screens block on those backend modules), demoable deliverable, estimate. Identify the critical path. Recommend a thin **walking-skeleton first** (login + theme + one real feature end-to-end, e.g., attendance) before fanning out.
17. **Risks & mitigations** — at least: backend modules not yet built (Fees/Messaging), the colon-path trap, dual-auth complexity, RTL correctness, WhatsApp-vs-app notification overlap/duplication, offline correctness, attachment upload pipeline absence, OTP deliverability.
18. **Open questions** — decisions only the product owner can make (single app vs per-role apps; is Super Admin in the app; do parents authenticate via OTP in-app or magic link; is a web admin still needed; attachment storage timeline; which modules are in mobile MVP vs later).

---

## 3. House rules you must encode in the plan (non-negotiable)

These are the team's standing conventions — bake them into the architecture, not as afterthoughts:

- **Immutability everywhere.** Models and state are immutable (`freezed`); never mutate in place — always copy-with. State management choice must reinforce this.
- **Many small, focused files** over few large ones. High cohesion, low coupling. Target 200–400 lines/file, 800 hard max. Organize **by feature/domain**, not by type.
- **Comprehensive error handling.** Every layer handles errors explicitly; user-facing messages are friendly and localized; detailed context is logged (PII-safe); never silently swallow. Map RFC 7807 → typed failures → UI.
- **Validate at boundaries.** Never trust API responses or user input; schema/typed parsing; fail fast with clear messages; mirror backend validation client-side for UX but treat server as source of truth.
- **Repository pattern** for all data access (consistent `findAll/findById/create/update/delete`-style interfaces); business/UI depends on abstractions, enabling test mocks.
- **Consistent API envelope handling** centralized in one place (matching the backend's actual envelope).
- **No hardcoded values** — colors/strings/endpoints/config via theme constants, ARB, and env config.
- **Security checklist before any commit** — no secrets in source, inputs validated, auth/authorization verified, error messages don't leak sensitive data.
- **Research & reuse first** — prefer proven packages and adaptable open-source patterns over net-new code; justify each dependency.
- **TDD-friendly** — structure so tests can be written first; ≥80% coverage target.

---

## 4. Output format & behaviour

- Deliver the plan as **one markdown document** with the section numbering above, a table of contents, tables where useful, and mermaid/ASCII diagrams for flows and key screens.
- Be **decisive**: recommend one option and justify briefly; list rejected alternatives in a line. Avoid “you could do X or Y” without a recommendation.
- Tag complexity per milestone (Low/Med/High) and call out the critical path.
- **Before finalizing**, list your open questions (Section 18) and **explicitly pause for confirmation** on: state-management choice, single-vs-multi app, MVP module set, and any backend gaps that block the roadmap. Do not write application code in this deliverable.
- When you cite an endpoint, header, or shape, **confirm it against the live backend source** and note any drift from Appendix A.

---

## Appendix A — Backend API surface (verified snapshot; re-confirm against source)

Base: `/api/v1`. Auth legend: **PUB** public · **S** staff JWT · **P** parent token · **SA** super-admin.

**Auth & identity**
- `POST /auth/login` (PUB) → access + refresh (staff)
- `POST /auth/refresh` (PUB) → rotate refresh
- `POST /auth/logout` (S) → revoke refresh
- `POST /parents/auth/request-otp` (PUB) → WhatsApp OTP
- `POST /parents/auth/verify-otp` (PUB) → opaque parent token
- `POST /parents/auth/logout` (P)
- `POST /devices/register` (S/P) → register FCM/APNs token
- `DELETE /devices/{deviceId}` (S/P) → unregister

**Schools (tenant)**
- `POST /schools` (SA) · `GET /schools` (SA) · `GET /schools/{id}` · `GET /schools/{id}/settings` · `PUT /schools/{id}/settings` (admin own) · `POST /schools/{id}/suspend` · `POST /schools/{id}/reactivate`

**Classes / students / structure** (S)
- `POST|GET /classes` · `GET|PATCH|DELETE /classes/{id}`
- `POST|GET /students` · `GET|PATCH|DELETE /students/{id}` · `POST /students:bulk-import` *(colon path — multipart CSV; mind §1.3.1)*
- `POST|GET /classes/{classId}/enrollments` · `DELETE /enrollments/{id}`
- `POST|GET /classes/{classId}/teachers` · `DELETE /teacher-assignments/{id}`
- `POST /parent-links` · `GET /parent-links/student/{studentId}` · `DELETE /parent-links/{id}`

**Announcements**
- `POST /announcements` (S) · `GET /announcements` (S) · `GET /announcements/{id}` (S) · `POST /announcements/{id}/recall` (S) · `GET /announcements/{id}/recipients` (S) · `POST /announcements/{id}/acknowledge` (P)

**Attendance**
- `POST /attendance/mark` (S, idempotent → triggers alert) · `POST /attendance/mark-all-present` (S) · `GET /attendance/roster?classId=&date=` (S) · `GET /attendance/history?studentId=&from=&to=` (S/P) · `POST /attendance/{id}/parent-response` (P)

**Homework** *(M9 — confirm live)*
- `POST /homework` · `POST /homework/{id}/publish` · `GET /homework` (S) · `GET /homework/{id}` (S/P) · `PATCH|DELETE /homework/{id}` · `GET /homework?childId=` (P feed) · `POST /homework/{id}/acknowledge` (P)

**Fees / Messaging / Reporting** — *planned per `IMPLEMENTATION_PLAN.md` §10; verify existence before building those app modules. Likely shapes: `/fee-items`, `/students/{id}/ledger`, `/payments`, `/conversations`, `/conversations/{id}/messages`, `/reports/*`, `/audit-logs`.*

**Webhooks (not client-facing):** `GET|POST /integrations/whatsapp/webhook`.

**Cross-cutting headers:** `Authorization: Bearer <token>`, `Idempotency-Key` (writes), `X-Request-Id`, `Accept-Language: ar|en`.

## Appendix B — Theme tokens (verified snapshot; re-confirm `globals.css`)

| Token | Value | Suggested Flutter mapping |
|---|---|---|
| primary | `#611f69` | `ColorScheme.primary` / M3 seed |
| cream | `#fcf5ef` | `surface` / `scaffoldBackgroundColor` |
| success | `#6b9f36` | semantic success / `tertiary` |
| orange (peach) | `#f9cd92` | accent / secondary container |
| border | `#e5e7eb` (gray-200) | `outlineVariant` / dividers |
| font | Inter | `google_fonts` `Inter` across `TextTheme` |
| heading scale | h1 ~48–72px, h2 ~36–48px, bold | `displayLarge`/`headlineLarge` |
| card radius | rounded-md (~6–8px) | `Card`/`shape` radius constant |
| section padding | `py-14` (~56px) | vertical spacing constant |
| elevation | `0 4px 20px rgba(110,127,185,0.1)` | soft `BoxShadow` for cards |

Component vocabulary to port: Hero, Course card, Mentor card, Testimonial, Newsletter CTA, Companies strip, Auth set, Breadcrumb, Loader/PreLoader, Skeleton, NotFound.

## Appendix C — Key references in the repos

- `E:\D2L\SchoolBridge_SRS_v1.0.md` — requirements (FR-/NFR-).
- `E:\D2L\docs\IMPLEMENTATION_PLAN.md` — backend architecture, data model, endpoint list, module roadmap (§10).
- `E:\D2L\docs\HANDOFF_M*.md` — module-by-module build notes and conventions (incl. the colon-path and idempotency lessons).
- `E:\D2L\src\main\java\com\schoolbridge\api\**` — ground-truth controllers/DTOs.
- `C:\...\si-education-1.0.0\src\app\globals.css` + `layout.tsx` + `components\**` — theme tokens and component vocabulary.

=== PROMPT ENDS HERE ===
