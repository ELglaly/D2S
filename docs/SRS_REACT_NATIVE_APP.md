# SchoolBridge Mobile App — Software Requirements Specification (React Native Client)

**Version:** 1.0
**Date:** 2026-08-17
**Backend verified against:** `E:\D2L` branch `p0-remediation` (Spring Boot 3.4.5, all 14 modules
built, P0 pre-launch remediation applied — see `docs/P0_REMEDIATION.md`)
**Audience:** React Native engineer(s) building the SchoolBridge mobile client

> This SRS is scenario-first: each functional requirement is expressed as a user scenario
> (Given/When/Then) mapped to the exact backend endpoint(s) it calls. Every endpoint, DTO field,
> and error shape below was read from the live source, not guessed — cite the file path in a
> comment if you find drift and flag it back.

---

## 1. Purpose

Specify what the SchoolBridge React Native app must do, scenario by scenario, so it can be built
directly from this document without re-deriving backend behavior. It is the single client for three
in-app roles — **School Admin**, **Teacher**, **Parent** — talking to the existing SchoolBridge REST
API.

## 2. Scope

**In scope (v1):** authentication (staff + parent), push device registration, school settings,
class/subject/student/enrollment/teacher-assignment management, grades, announcements, attendance,
homework, attachments (upload/download), notification preferences.

**Out of scope (v1), tracked as Phase 2:**
- **AI Assistant** (`/api/v1/assistant/*`, `/api/v1/conversations/*`) — ships dark
  (`ASSISTANT_ENABLED=false` by default per school). Do not build this until a school has it
  switched on; see §9.
- **Platform Admin** (`PlatformAdmin` cross-tenant super-user, school onboarding/suspend) — this is
  an internal SchoolBridge-staff surface, not a school-user-facing one. Assume a separate internal
  tool.
- **Fees and direct teacher↔parent messaging** — do not exist in the current backend. If product
  wants them, they are new backend modules first, app work second.

**Assumption carried from prior planning (confirm with product if wrong):** one binary, three
role-aware experiences — not three separate apps. WhatsApp remains a parallel notification channel
the backend already sends; the app is not replacing it, just adding a native surface.

## 3. Definitions & Roles

| Term | Meaning |
|---|---|
| Tenant / School | Isolation boundary. Every staff user and every record belongs to exactly one school. |
| `SUPER_ADMIN` | A tenant-scoped role (not the cross-tenant `PlatformAdmin`) — full access within one school. |
| `SCHOOL_ADMIN` | Manages users, classes, students, subjects, announcements, settings for their school. |
| `TEACHER` | Takes attendance, posts homework/grades, sends class-scoped announcements for classes they teach. |
| `PARENT` | Linked to one or more students via `ParentStudentLink`. Read-mostly: feed, acknowledge, respond. |
| `ApiResponse<T>` | The success envelope every JSON response is wrapped in (see §4.2). |
| `ProblemDetail` | The RFC 7807 error shape every non-2xx JSON response uses (see §4.3). |

`UserRole` is single-valued — a staff user has exactly one of `SUPER_ADMIN`, `SCHOOL_ADMIN`,
`TEACHER` (not a set). Parents authenticate through a completely separate flow and are not a `User`
role at all — they are resolved through `ParentStudentLink`.

## 4. System Context — facts the client must honor exactly

### 4.1 Base URL & routing

- All client-facing endpoints are under `/api/v1`.
- The WhatsApp webhook (`/integrations/whatsapp/*`) is provider-facing, not client-facing — ignore it.
- **One known non-slash path**: `POST /api/v1/students:bulk-import` still uses an AIP colon-verb.
  Generic HTTP clients (including RN's `fetch`/`axios` through some proxies) percent-encode `:` to
  `%3A`, which 404s against Spring's routing. **Send the literal colon un-encoded** for this one
  call, or defer bulk-import to a later phase — it's an admin CSV-import convenience, not core path.
  Every other mutating endpoint is slash-style (`/homework/{id}/publish`, not `/homework/{id}:publish`).

### 4.2 Success envelope

Every non-empty 2xx JSON response is wrapped:

```json
// single resource
{ "data": { ... }, "meta": null }

// paginated list
{ "data": [ ... ], "meta": { "page": 0, "size": 20, "totalElements": 137, "totalPages": 7 } }
```

204 No Content responses (logout, delete) have no body — don't try to parse one.

**Auth responses (`AuthResponse`, `VerifyOtpResponse`, `RequestOtpResponse`) are wrapped the same
way** — `response.data.accessToken`, not `response.accessToken`. Build one response-unwrapping layer
in the API client and never bypass it.

### 4.3 Error envelope

Every non-2xx JSON response is an **unwrapped** RFC 7807 `ProblemDetail` — do not look for a `data`
key on errors:

```json
{
  "type": "https://schoolbridge.app/errors/validation",
  "title": "Validation failed",
  "status": 422,
  "detail": "Localized message",
  "instance": "/api/v1/homework",
  "traceId": "a1b2c3...",
  "errors": [ { "field": "title", "message": "must not be blank" } ]
}
```

`errors[]` is present **only** on 422 validation failures (bean validation or constraint violation);
every other error type omits it. `traceId` is always present (empty string if MDC had none — treat
empty as absent). Map `type`/`status` to a typed `Failure` in the client; surface `errors[]` to form
fields; show `traceId` in a "report a problem" affordance, never raw stack content (the backend
never sends one).

`ErrorType` → HTTP status, for client-side switch/typed-failure mapping: `NOT_FOUND(404)`,
`VALIDATION(422)`, `AUTHENTICATION(401)`, `AUTHORIZATION(403)`, `CONFLICT(409)`,
`RATE_LIMIT(429)`, `INTEGRATION(502)`, `TENANT_SECURITY(403)`, `INTERNAL(500)`.

### 4.4 Authentication — two independent schemes

**Staff (`SUPER_ADMIN`/`SCHOOL_ADMIN`/`TEACHER`) — JWT + refresh:**
- `POST /api/v1/auth/login` — body `{ email, password }` → `{ accessToken, refreshToken,
  accessExpiresAt, tokenType: "Bearer" }`. Access token is short-lived; `accessExpiresAt` is an ISO
  `Instant` — schedule silent refresh ahead of it, don't wait for a 401.
- `POST /api/v1/auth/refresh` — body `{ refreshToken }` → new pair. **Single-use**: the old refresh
  token is revoked the instant this call succeeds. Never call refresh twice with the same token
  (e.g. from two racing requests) — queue concurrent 401s behind one refresh call.
- `POST /api/v1/auth/logout` — body `{ refreshToken }` → 204, idempotent (unknown tokens still 204).
- No tenant header is sent by the client — `schoolId` is resolved server-side from the JWT.

**Parent — OTP + opaque session token:**
- `POST /api/v1/parents/auth/request-otp` — body `{ phone }` (E.164, `^\+[1-9]\d{6,14}$`) → `{
  ticketId }`. **Always 200**, whether or not the phone is known — anti-enumeration by design; don't
  treat 200 as "user exists." **Rate-limited per phone number** — handle 429 with a cooldown UI, not
  a retry loop.
- `POST /api/v1/parents/auth/verify-otp` — body `{ ticketId, code }` (6 digits) → `{ token,
  schoolId, tokenType: "Bearer", expiresInSeconds: 86400 }`. 400 on wrong code or expired ticket —
  distinguish this from a network error in the UI (let the user retry the code, don't discard the
  ticket).
- `POST /api/v1/parents/auth/logout` — body `{ token }` → 204, idempotent.
- The token is a **24-hour sliding-TTL opaque bearer token**, not a JWT — there is no refresh flow.
  When it expires, the only recovery is re-running OTP. Design the session layer so "current
  credential" is an interface both schemes satisfy, but the refresh-vs-re-auth branching happens in
  one place.
- A parent linked to more than one school: `verify-otp` returns one `schoolId` — confirm with
  backend whether multi-school parents get one token per school or a school-selection step; nothing
  in the current `ParentAuthService` surface suggests a switch-school call. Treat as **single-school
  parent** for v1 and flag multi-school as an open question (§9).

**Every authenticated request:** `Authorization: Bearer <token>`, regardless of which scheme issued
it.

### 4.5 Headers the client must send

| Header | When | Why |
|---|---|---|
| `Authorization: Bearer <token>` | every authenticated call | staff JWT or parent opaque token |
| `Accept-Language: ar` or `en` | every call | drives which `messages_{ar,en}.properties` key resolves in error `detail`/`title` |
| `Idempotency-Key` | POST/PUT/PATCH that create or mutate (e.g. `attendance/mark`, `homework`, `announcements`) | generate one UUID per logical user action; **reuse the same key on retry of that same action**, never generate a new one just because the first attempt timed out — that's what makes retry-after-timeout safe |

### 4.6 Pagination

List endpoints return `meta.page/size/totalElements/totalPages` per §4.2. Request pagination via
standard Spring `page`/`size` query params unless a controller documents otherwise — verify per
endpoint via the OpenAPI spec (`/v3/api-docs`) before wiring the first list screen.

### 4.7 i18n / RTL

Every user-facing string on the backend has both `messages_ar.properties` and
`messages_en.properties` entries — i18n parity is a hard backend requirement, so error `detail`
text will always be localized if `Accept-Language` is sent correctly. The app must support full
**Arabic RTL** (mirrored layouts, not just translated strings).

### 4.8 Money & time

Currency (none in v1 — no fees module) is out of scope. All timestamps from the API are UTC ISO-8601
`Instant` strings — convert to local device time at render, and back to UTC before sending.

---

## 5. Non-Functional Requirements

- **NFR-P1 — Attendance alert latency:** the backend targets a 5-minute end-to-end SLA from an
  `ABSENT` mark to the parent's alert landing. The app has no control over this once the mark is
  submitted, but attendance-marking UX must not add client-side delay (no debounce longer than
  needed for double-tap protection).
- **NFR-P2 — 3G-tolerant:** rosters, feeds, and attachment tickets should render usefully on slow
  connections — paginate, don't over-fetch, show skeletons not spinners-forever.
- **NFR-S1 — No PII in logs.** Names, phone numbers, message bodies are encrypted at rest
  server-side; treat the same fields as sensitive in client logs/crash reports — never log a full
  request/response body that contains them.
- **NFR-S2 — Secure token storage.** Both token types (JWT pair and parent opaque token) go in
  platform secure storage (Keychain/Keystore via e.g. `react-native-keychain`), never
  `AsyncStorage`/plain state persisted to disk.
- **NFR-A1 — Attendance preference cannot be muted.** See §7.9 — this is enforced server-side; the
  client must not offer a toggle that implies it can be turned off.
- **NFR-O1 — Offline-tolerant writes.** Attendance marking, homework acknowledgement, and
  announcement acknowledgement are candidates for optimistic UI + queued retry using the
  `Idempotency-Key` contract in §4.5, so a flaky connection doesn't produce a duplicate side effect
  or a lost action.

---

## 6. Role → Feature Visibility Summary

| Feature area | SCHOOL_ADMIN | TEACHER | PARENT |
|---|---|---|---|
| School settings | read/write | — | — |
| Users (staff) | manage | — | — |
| Classes / Subjects / Enrollment / Teacher assignment | manage | read own | — |
| Students | manage | read | — (resolved via own children) |
| Parent links | manage | — | — |
| Grades | manage | create/update own class | read own children |
| Announcements | send/manage/recall | send (own classes) | read + acknowledge |
| Attendance | read/reports | record | read history + respond to alert |
| Homework | manage | create/publish/edit own | read (by child) + acknowledge |
| Attachments | upload/read/delete | upload/read/delete | read/download (as recipient only) |
| Notification preferences | own | own | own |
| Device registration | own | own | own |

Row-ownership checks ("this teacher teaches this class", "this parent owns this child") are enforced
server-side via `@PreAuthorize` alongside the coarser `@RequirePermission` — the client should still
gate navigation by role for UX, but must treat every server 403 as authoritative, not a client bug.

---

## 7. User Scenarios

Each scenario: **ID**, **Actor**, **Given/When/Then**, endpoint(s), and notes. IDs are stable —
reference them in tickets/tests.

### 7.1 Staff Authentication

**AUTH-1 — Staff login**
- Given a School Admin or Teacher with valid credentials
- When they submit email + password
- Then `POST /auth/login` returns an access/refresh pair; store both in secure storage and route to
  the role-appropriate home screen
- Errors: 401 (bad credentials) → inline form error, no field-level detail (don't reveal which of
  email/password was wrong); 422 (missing fields) → field-level from `errors[]`

**AUTH-2 — Silent token refresh**
- Given a stored refresh token and an access token nearing `accessExpiresAt`
- When the app is foregrounded or a request would otherwise 401
- Then call `POST /auth/refresh` once (queue any concurrent requests behind it), replace both
  tokens, retry the queued requests
- Error: refresh 401 (expired/revoked) → clear session, route to login, show "session expired"

**AUTH-3 — Staff logout**
- Given a logged-in staff user
- When they tap logout
- Then call `DELETE`-equivalent `POST /auth/logout` with the refresh token, clear local session
  regardless of response (logout is idempotent/best-effort), and unregister the device token
  (DEV-2) before clearing credentials

### 7.2 Parent Authentication

**AUTH-4 — Request OTP**
- Given a parent on the login screen
- When they enter a phone number in E.164 format
- Then call `POST /parents/auth/request-otp`, store the returned `ticketId`, show a 6-digit code
  entry screen with a resend cooldown
- Errors: 422 (malformed phone) → inline; 429 (rate-limited) → cooldown timer, no retry button until
  it elapses. **Do not** infer from a 200 that the number is registered — always show the same
  "check WhatsApp for a code" message.

**AUTH-5 — Verify OTP**
- Given a `ticketId` from AUTH-4
- When the parent enters the 6-digit code
- Then call `POST /parents/auth/verify-otp` with `{ ticketId, code }`; on success store the opaque
  token + `schoolId`, route to the parent home
- Errors: 400 (wrong code or expired ticket) → let the parent retry the code without re-requesting
  OTP unless the ticket itself is expired (distinguish via `detail`); after N wrong attempts, offer
  "resend code" which re-runs AUTH-4

**AUTH-6 — Parent session expiry**
- Given a parent token older than 24h since last use (sliding TTL — an active session keeps
  extending; an idle one lapses)
- When any API call 401s
- Then there is no refresh — clear session and route straight to phone-entry (AUTH-4), no silent
  recovery is possible

**AUTH-7 — Parent logout**
- Given a logged-in parent
- When they tap logout
- Then call `POST /parents/auth/logout` with the token, clear local session, unregister device token

### 7.3 Device / Push Registration

**DEV-1 — Register device token**
- Given a successful login (either scheme) or an FCM token rotation event
- When the app has a fresh FCM token
- Then call `POST /devices/register` with `{ platform: "ANDROID"|"IOS", fcmToken, deviceId }`
  (upsert — safe to call repeatedly with the same `deviceId`)
- Notes: call this on every app start post-auth, not just first login, since FCM tokens rotate

**DEV-2 — Unregister device**
- Given a logout
- When the session is being torn down
- Then call `DELETE /devices/{deviceId}` before clearing the token from storage, so a signed-out
  device stops receiving push

### 7.4 School Settings (Admin)

**ADM-1 — View school settings**
- Given a School Admin
- When they open Settings
- Then `GET /schools/{id}/settings` — show quiet hours, default language, subscription tier
  (read-only)

**ADM-2 — Update school settings**
- Given a School Admin editing quiet hours or default language
- When they save
- Then `PUT /schools/{id}/settings`; on 422 show field errors; this is the window
  `NotificationPreferencesResponse.effectiveQuietHours*` inherits from when a user hasn't overridden it

### 7.5 Academic Structure (Admin, Teacher-read)

**CLS-1 — Admin creates a class**
- `POST /classes` — Given class name/grade, When saved, Then it's listable via `GET /classes`

**CLS-2 — Admin manages subjects**
- `POST /subjects`, `PATCH /subjects/{id}`, `DELETE /subjects/{id}` — per-school catalog, distinct
  from grade *records*

**CLS-3 — Admin assigns a subject to a class**
- `POST /classes/{classId}/subjects` then `POST
  /classes/{classId}/subjects/{subjectId}/teachers` to assign the teaching staff member

**CLS-4 — Admin creates a student**
- `POST /students` — Given student profile fields, When saved, then enroll via `POST
  /classes/{classId}/enrollments`

**CLS-5 — Admin links a parent to a student**
- `POST /parent-links` with `relationshipType` (`MOTHER`/`FATHER`/`GUARDIAN`) — this is the record
  every parent-facing screen resolves "my children" through; there is no bare-student-id parent
  lookup

**CLS-6 — Teacher views own classes**
- `GET /classes/my-classes` — Given a logged-in teacher, populates the class picker for
  attendance/homework/grades screens

**CLS-7 — Parent views linked children**
- `GET /parents/me/children` — the entry point for every parent screen; cache this at login and key
  a "child switcher" UI off it (see cross-cutting SC-C4)

**CLS-8 (deferred) — Admin bulk-imports students via CSV**
- `POST /students:bulk-import` (multipart) — flagged in §4.1 for the colon-path trap; build after
  the core flows are stable, and centralize URL construction so this one path is handled in one
  place

### 7.6 Grades

**GRD-1 — Teacher records a grade**
- Given a Teacher viewing their class roster for a subject
- When they enter a score for a student
- Then `POST /grades` — gated by `GRADE_CREATE`

**GRD-2 — Teacher/Admin updates or deletes a grade**
- `PATCH /grades/{id}` / `DELETE /grades/{id}` — gated by `GRADE_UPDATE`/`GRADE_DELETE`

**GRD-3 — Parent views a child's grades**
- `GET /grades?studentId={id}` — Given a parent has selected a child (CLS-7), list grade records for
  that student; gated by `GRADE_READ`

### 7.7 Announcements

**ANN-1 — Admin/Teacher composes and sends an announcement**
- Given a scope picker (`SCHOOL`/`GRADE`/`CLASS`/`CUSTOM`), a language, optional attachment(s)
  (§7.10), and optional "requires acknowledgement" flag
- When submitted
- Then `POST /announcements` with an `Idempotency-Key`; status starts `DRAFT`→ becomes
  `SCHEDULED`/`SENDING`/`SENT` server-side. Recipient fan-out is async — don't block the compose UI
  waiting for delivery to finish.

**ANN-2 — Admin/Teacher recalls a sent announcement**
- `POST /announcements/{id}/recall` — Given a mis-sent announcement, moves status to `RECALLED`;
  show a confirmation dialog (destructive, visible to all recipients) before calling this

**ANN-3 — Admin/Teacher views delivery/ack status**
- `GET /announcements/{id}/recipients` — per-recipient `DeliveryStatus` (`QUEUED`, `DEFERRED`, …)
  and ack state; render as a list with per-row status chips, not just an aggregate count

**ANN-4 — Parent views announcement feed**
- `GET /announcements` (scoped server-side to what this parent's children can see) — paginate

**ANN-5 — Parent acknowledges an announcement**
- `POST /announcements/{id}/acknowledge` — Given an announcement flagged as requiring ack, When the
  parent taps "acknowledge", Then call this; **a parent with two children at the same school clears
  every recipient row they hold for that announcement in one call** (fixed multi-child ack bug — see
  `docs/P0_REMEDIATION.md` §1.4), so the client only needs to fire this once, not once per child

### 7.8 Attendance

**ATT-1 — Teacher views the daily roster**
- `GET /attendance/roster?classId=&date=` — Given a teacher opens the attendance screen for their
  class, default `date` to today, show one row per student with current status if already marked

**ATT-2 — Teacher marks attendance**
- `POST /attendance/mark` — Given a per-student status tap (`PRESENT`/`ABSENT`/`LATE`/`EXCUSED`),
  send with an `Idempotency-Key` unique per (student, date) so a retry after a timeout doesn't
  double-fire the absence alert pipeline. Marking `ABSENT` triggers a server-side alert to the
  parent — the client does nothing further.

**ATT-3 — Teacher marks the whole roster present**
- `POST /attendance/mark-all-present` — one-tap bulk action, given the common case where most of the
  class is present; still allow per-student override afterward

**ATT-4 — Parent/Teacher views attendance history**
- `GET /attendance/history?studentId=&from=&to=` — Given a date range, paginate if long; parents see
  only their own children's history (server-enforced)

**ATT-5 — Parent responds to an absence alert**
- `POST /attendance/{id}/parent-response` — Given a push notification for an absence alert, deep-link
  into this record and let the parent submit a response (e.g. confirming/explaining the absence)

### 7.9 Homework

**HW-1 — Teacher creates a homework item**
- `POST /homework` — starts `DRAFT`; Given a title/description/due date/optional attachments and
  target class

**HW-2 — Teacher publishes homework**
- `POST /homework/{id}/publish` — `DRAFT → PUBLISHED`; this materializes per-parent recipient rows
  and fires reminders server-side. Only `PUBLISHED` items are visible to parents — a `DRAFT` never
  appears in the parent feed even if fetched by id.

**HW-3 — Teacher edits or deletes homework**
- `PATCH /homework/{id}` / `DELETE /homework/{id}` — Given still-editable state; confirm before
  delete since it's destructive once published

**HW-4 — Teacher views recipient ack/delivery status**
- `GET /homework/{id}/recipients` — `HomeworkDeliveryStatus`: `PENDING`, `DEFERRED`, `SUPPRESSED`,
  `SENT`, `FAILED` — same list-with-status-chip pattern as ANN-3

**HW-5 — Parent views homework feed by child**
- `GET /homework?childId=` — Given a selected child (CLS-7), list `PUBLISHED` homework for that
  child's class(es), most-recent/due-soonest first

**HW-6 — Parent acknowledges homework**
- `POST /homework/{id}/acknowledge` — same pattern as ANN-5

### 7.10 Attachments (upload/download)

Backend never touches file bytes — every upload/download is a presigned URL the app talks to
directly against object storage. This is a 3-call dance; build it as one reusable module since
announcements and homework both use it.

**ATC-1 — Request an upload URL**
- `POST /attachments` with `{ fileName, contentType, sizeBytes }` (exact byte count, known before
  the call — read the file size first) → `201` with `{ attachmentId, uploadUrl, method,
  requiredHeaders, expiresAt }`
- Gated by `ATTACHMENT_UPLOAD`; 422 if size exceeds the server cap or content type isn't allow-listed

**ATC-2 — Perform the upload**
- Given the ticket from ATC-1
- When the app has the ticket
- Then `PUT` the file bytes to `uploadUrl`, sending **every header in `requiredHeaders` verbatim** —
  the presigned URL's signature covers them, so a missing/altered header 403s from the object store
  in a way that looks like (but is not) a credentials failure. Do this before `expiresAt`; if it
  lapses, go back to ATC-1 for a new ticket, don't retry the stale URL.

**ATC-3 — Complete the upload**
- `POST /attachments/{id}/complete` (no body) — Given the PUT in ATC-2 succeeded, When called, Then
  the backend verifies object size, sniffs real content type, and AV-scans; response carries the
  terminal `AttachmentStatus`: `CLEAN` (attach it to the homework/announcement being composed),
  `REJECTED` (show `rejectionReason`, let the user pick a different file), or the object is deleted
  server-side if infected. 409 if the PUT never landed — surface as "upload didn't complete, try
  again" and restart from ATC-1.

**ATC-4 — Download an attachment**
- `GET /attachments/{id}/download` → short-lived presigned GET, only issued when status is `CLEAN`.
  Mint this **per request, right before opening/downloading** — never cache the URL, it expires in
  minutes. 409 if not yet `CLEAN`.

**ATC-5 — Delete an attachment**
- `DELETE /attachments/{id}` — 409 while still referenced by a published homework item or
  announcement; only allow delete from a compose-in-progress (unpublished) draft in the UI to avoid
  surfacing that 409 as a confusing dead end

### 7.11 Notification Preferences

**NOT-1 — View own preferences**
- `GET /notifications/preferences` — Given any authenticated user (staff or parent), returns every
  category (`ANNOUNCEMENT`, `HOMEWORK`, `ATTENDANCE`) with defaults filled in even if never
  configured, plus `effectiveQuietHours*` (the real window after school-level inheritance) —
  render this, not the raw override fields, as "your current quiet hours"

**NOT-2 — Update preferences**
- `PUT /notifications/preferences` — **whole-set replace**, not a patch: send every category the
  user currently has, since omitted categories keep their *stored* value but a client that only
  sends a partial list on every save risks drifting from what's shown. Quiet hours: supply both
  `quietHoursStart`/`quietHoursEnd` or neither (half a window is rejected with 422) — "inherit
  school default" = send both as null.
- **`ATTENDANCE` cannot be disabled** — its `isMutable()` is `false` server-side and the write API
  rejects an attempt to turn it off with 422. Render the Attendance row as locked/always-on in the
  UI; don't let the user toggle it and then show a confusing validation error.

---

## 8. Cross-Cutting Scenarios

**SC-C1 — Session expiry mid-action (either role)**
- Given a form half-filled (e.g. composing an announcement) and a token that expires
- When a submit call 401s
- Then attempt silent refresh (staff) or route to re-auth (parent) **without discarding the
  in-progress form state** — retry the original call after recovery, or restore the draft after
  re-login

**SC-C2 — Optimistic write with idempotency (attendance, acknowledgements)**
- Given a flaky connection
- When the user taps "mark absent" and the request hangs
- Then show the optimistic state immediately, keep the same `Idempotency-Key` on any automatic
  retry, and reconcile with the server response when it arrives — never generate a fresh key for a
  retry of the same tap, or a timeout-then-retry becomes a double side effect

**SC-C3 — Language switch (AR/EN) + RTL**
- Given a user changes app language
- When they do
- Then persist the choice, set `Accept-Language` on all subsequent requests, and mirror the entire
  layout for Arabic — this is a full RTL requirement, not string substitution

**SC-C4 — Parent with multiple children**
- Given a parent linked to 2+ students (`GET /parents/me/children` returns >1)
- When they use any child-scoped screen (grades, homework, attendance)
- Then show a persistent child switcher; every child-scoped fetch (`GRD-3`, `HW-5`, `ATT-4`) must
  carry the currently-selected child's id — never assume a single child

**SC-C5 — Push notification tap → deep link**
- Given a push notification (announcement, homework, absence alert)
- When the user taps it
- Then route directly into the relevant screen/record (e.g. `ATT-5`'s parent-response screen for an
  absence alert) rather than dropping them on a generic home screen

**SC-C6 — Error display consistency**
- Given any non-2xx response
- When rendering it
- Then map `ErrorType`/`status` to a consistent UI pattern: 401/403 → auth/permission messaging
  (never "something went wrong"), 404 → "not found" empty state, 422 → inline field errors from
  `errors[]`, 429 → cooldown/backoff messaging, 5xx/`INTEGRATION` → generic retry-able error with
  `traceId` visible in a "report a problem" affordance

---

## 9. Out of Scope for v1 — Explicitly Deferred

| Item | Why deferred | Reference |
|---|---|---|
| AI Assistant (`/assistant/*`, `/conversations/*`) | Ships dark; off by default per school; needs a data-processing agreement decision before any real tenant enables it | `docs/P0_REMEDIATION.md` "Decide the third-party inference question" |
| Fees / billing | No backend module exists | — |
| Direct teacher↔parent messaging | No backend module exists | — |
| Multi-school parent switching | `verify-otp` returns a single `schoolId`; no switch-school endpoint found in `ParentAuthService` | confirm with backend before building — open question |
| CSV bulk student import (`CLS-8`) | Colon-path trap (§4.1) + lower priority than core flows | `docs/COMMON_MISTAKES.md` #6 |
| Platform Admin (school onboarding/suspend) | Internal SchoolBridge-staff tool, not a school-user surface | — |

---

## 10. Appendix — Condensed Endpoint Reference

See `.claude/rules/java/schoolbridge-api-map.md` in the backend repo for the full, authoritative
map (kept in sync with the controllers). Summary of everything referenced above:

```
Auth (staff):      POST /auth/login, /auth/refresh, /auth/logout
Auth (parent):     POST /parents/auth/request-otp, /verify-otp, /logout
Devices:           POST /devices/register · DELETE /devices/{id}
Schools:           GET/PUT /schools/{id}/settings
Classes:           POST/GET /classes · GET /classes/my-classes · POST/GET /classes/{id}/enrollments
                   · POST/GET /classes/{classId}/subjects/{subjectId}/teachers
Students:          POST/GET/PATCH/DELETE /students · POST /students:bulk-import
Parent links:      POST /parent-links · GET /parents/me/children
Subjects:          POST/GET/PATCH/DELETE /subjects
Grades:            POST/GET/PATCH/DELETE /grades
Announcements:     POST/GET /announcements · POST /{id}/recall · GET /{id}/recipients
                   · POST /{id}/acknowledge
Attendance:        POST /attendance/mark · /mark-all-present · GET /roster · /history
                   · POST /{id}/parent-response
Homework:          POST/GET/PATCH/DELETE /homework · POST /{id}/publish · GET /{id}/recipients
                   · GET /homework?childId= · POST /{id}/acknowledge
Attachments:       POST /attachments · POST /{id}/complete · GET /{id} · GET /{id}/download
                   · DELETE /{id}
Notifications:     GET/PUT /notifications/preferences
```

Auth legend: staff routes need a JWT from `/auth/login`; parent-facing reads/writes need the opaque
token from `/parents/auth/verify-otp`; both use the same `Authorization: Bearer` header.
