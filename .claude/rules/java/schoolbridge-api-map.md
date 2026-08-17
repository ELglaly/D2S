# SchoolBridge API Map

The REST surface as it exists in the controllers. All endpoints are under `/api/v1/` unless noted
(the WhatsApp webhook and diagnostics controllers are mounted at `/integrations/whatsapp/*`, outside
`/api/v1`). Grounded in `@GetMapping`/`@PostMapping`/etc. found in
`src/main/java/com/schoolbridge/api/**/*Controller.java`. Verify per-endpoint role/permission
requirements in the controller before relying on them — this map lists paths and purpose, not the
full `@RequirePermission` grant for every route.

> **Auth column legend**: "—" = no auth (public); "JWT" = bearer token required; specific
> `@RequirePermission` values are named in `.claude/rules/java/schoolbridge-modules.md` under
> "Authorization Model" — check the controller for which one gates a given route.

## Auth — `/api/v1/auth`, `/api/v1/parents/auth`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/auth/login` | — | Staff/admin email+password login → JWT. |
| POST | `/api/v1/auth/refresh` | — | Rotate refresh token for a new access token. |
| POST | `/api/v1/auth/logout` | JWT | Revoke the refresh token. |
| POST | `/api/v1/parents/auth/request-otp` | — | Request an OTP for parent login (rate-limited — `OtpRequestRateLimiter`). |
| POST | `/api/v1/parents/auth/verify-otp` | — | Verify OTP → JWT. |
| POST | `/api/v1/parents/auth/logout` | JWT | Revoke the parent's refresh token. |

## Devices — `/api/v1/devices`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/register` | JWT | Register a push device token (`DevicePlatform`: `ANDROID`, `IOS`). |
| DELETE | `/{deviceId}` | JWT | Unregister a device. |

## Users — `/api/v1/schools/{schoolId}/users`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/` | JWT | Create a user (staff onboarding). |
| GET | `/` | JWT | List users for the school. |
| GET | `/{id}` | JWT | Get a user. |

## Schools — `/api/v1/schools`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/` | JWT | Onboard a school (platform admin). |
| GET | `/` | JWT | List schools. |
| GET | `/{id}` | JWT | Get a school. |
| GET | `/{id}/settings` | JWT | Get school settings. |
| PUT | `/{id}/settings` | JWT | Update school settings. |
| POST | `/{id}/suspend` | JWT | Suspend a school (`SchoolStatus.SUSPENDED`). |
| POST | `/{id}/reactivate` | JWT | Reactivate a suspended school. |

## Classes — `/api/v1/classes`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/` | JWT | Create a `SchoolClass`. |
| GET | `/` | JWT | List classes. |
| GET | `/by-teacher` | JWT | Classes for a given teacher. |
| GET | `/my-classes` | JWT | Classes for the calling teacher. |
| GET | `/{id}` | JWT | Get a class. |
| PATCH | `/{id}` | JWT | Update a class. |
| DELETE | `/{id}` | JWT | Delete a class. |
| POST | `/classes/{classId}/enrollments` | JWT | Enroll a student. |
| GET | `/classes/{classId}/enrollments` | JWT | List enrollments for a class. |
| DELETE | `/enrollments/{id}` | JWT | Remove an enrollment. |
| POST | `/classes/{classId}/subjects` | JWT | Assign a subject to a class. |
| GET | `/classes/{classId}/subjects` | JWT | List a class's subjects. |
| DELETE | `/class-subjects/{id}` | JWT | Unassign. |
| POST | `/classes/{classId}/subjects/{subjectId}/teachers` | JWT | Assign a teacher to a class subject. |
| GET | `/classes/{classId}/subjects/{subjectId}/teachers` | JWT | List teachers for a class subject. |
| GET | `/classes/{classId}/teacher-subject-assignments` | JWT | List assignments for a class. |
| DELETE | `/teacher-subject-assignments/{id}` | JWT | Remove an assignment. |
| GET | `/students/{studentId}/subjects` | JWT | Subjects a student is enrolled in. |

## Students — `/api/v1/students`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/` | JWT | Create a student. |
| GET | `/` | JWT | List students. |
| GET | `/{id}` | JWT | Get a student. |
| PATCH | `/{id}` | JWT | Update a student (`StudentStatus`). |
| DELETE | `/{id}` | JWT | Delete a student. |
| POST | `/:bulk-import` | JWT | Multipart bulk import. **Colon-verb path — a known pre-existing ADR-006 violation, not a pattern to reuse.** |

## Parent links — `/api/v1/parents`, `/api/v1/parent-links`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/parents/me/children` | JWT | The calling parent's linked children. |
| POST | `/api/v1/parent-links` | JWT | Create a `ParentStudentLink`. |
| GET | `/api/v1/parent-links/student/{studentId}` | JWT | Links for a student. |
| DELETE | `/api/v1/parent-links/{id}` | JWT | Remove a link. |

## Subjects — `/api/v1/subjects`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/` | JWT | Create a subject. |
| GET | `/` | JWT | List subjects. |
| GET | `/{id}` | JWT | Get a subject. |
| PATCH | `/{id}` | JWT | Update a subject. |
| DELETE | `/{id}` | JWT | Delete a subject. |

## Grades — `/api/v1/grades`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/` | JWT | Create a grade record. |
| GET | `/?studentId=` | JWT | Grades for a student. |
| GET | `/?classId=` | JWT | Grades for a class. |
| GET | `/{id}` | JWT | Get a grade record. |
| PATCH | `/{id}` | JWT | Update a grade record. |
| DELETE | `/{id}` | JWT | Delete a grade record. |

## Announcements — `/api/v1/announcements`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/` | JWT | Create/send an announcement (`AnnouncementScope`: `SCHOOL`/`GRADE`/`CLASS`/`CUSTOM`). |
| GET | `/` | JWT | List announcements. |
| GET | `/{id}` | JWT | Get an announcement. |
| POST | `/{id}/recall` | JWT | Recall a sent announcement. |
| GET | `/{id}/recipients` | JWT | List recipients + delivery/ack status. |
| POST | `/{id}/acknowledge` | JWT | Parent acknowledges. |

## Attendance — `/api/v1/attendance`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/{id}` | JWT | Get an attendance record. |
| POST | `/mark` | JWT | Mark attendance for a student. |
| POST | `/mark-all-present` | JWT | Bulk mark a roster present. |
| GET | `/roster` | JWT | Class roster for a date. |
| GET | `/history` | JWT | Attendance history (student or class). |
| POST | `/{id}/parent-response` | JWT | Parent responds to an absence alert. |

## Homework — `/api/v1/homework`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/` | JWT | Create a homework item (`HomeworkStatus.DRAFT`). |
| POST | `/{id}/publish` | JWT | Publish (`DRAFT → PUBLISHED`), materializes recipients + fires reminders. |
| GET | `/` | JWT | List homework items. |
| GET | `/?childId=` | JWT | Homework visible to a parent's child. |
| GET | `/{id}` | JWT | Get a homework item. |
| GET | `/{id}/recipients` | JWT | Delivery/ack status per recipient. |
| PATCH | `/{id}` | JWT | Update a homework item. |
| DELETE | `/{id}` | JWT | Delete a homework item. |
| POST | `/{id}/acknowledge` | JWT | Parent acknowledges. |

## Attachments — `/api/v1/attachments`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/` | JWT | Request a presigned upload URL (`AttachmentStatus.PENDING`). |
| POST | `/{id}/complete` | JWT | Client confirms the PUT landed → triggers MIME sniff + AV scan. |
| GET | `/{id}` | JWT | Attachment metadata/status. |
| GET | `/{id}/download` | JWT | Presigned GET (only when `CLEAN`). |
| DELETE | `/{id}` | JWT | Delete an attachment. |

## Notifications — `/api/v1/notifications`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/preferences` | JWT | Own quiet hours / channel order / category opt-outs. |
| PUT | `/preferences` | JWT | Update preferences. |

## Assistant — `/api/v1/assistant`, `/api/v1/conversations`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/assistant/ask` | JWT | Ask the assistant (ships dark — `ASSISTANT_ENABLED`). |
| POST | `/api/v1/assistant/actions/{token}/confirm` | JWT | Confirm a pending destructive `ACTION` tool call. |
| POST | `/api/v1/assistant/actions/{token}/cancel` | JWT | Cancel a pending confirmation. |
| GET/PUT | `/api/v1/assistant/settings` | JWT | Per-school assistant settings (`ASSISTANT_SETTINGS_MANAGE`). |
| POST/GET | `/api/v1/assistant/knowledge` | JWT | Upload/list RAG knowledge documents (`DOCUMENT_MANAGE`). |
| DELETE | `/api/v1/assistant/knowledge/{id}` | JWT | Remove a knowledge document. |
| POST | `/api/v1/conversations` | JWT | Start a conversation. |
| GET | `/api/v1/conversations` | JWT | List own conversations. |
| DELETE | `/api/v1/conversations/{id}` | JWT | Delete a conversation. |
| POST | `/api/v1/conversations/{id}/messages` | JWT | Send a message; **SSE stream** response (`text/event-stream`). |

## Admin (authz) — `/api/v1/admin/authz`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/permissions` | JWT | List all `Permission` values. |
| GET | `/roles/{role}/permissions` | JWT | Permissions granted to a role. |
| POST | `/roles/{role}/permissions/{permission}` | JWT | Grant (`MANAGE_ROLES`, seeded to `SUPER_ADMIN`). |
| DELETE | `/roles/{role}/permissions/{permission}` | JWT | Revoke. |

## Integrations (WhatsApp) — `/integrations/whatsapp/*`

Outside `/api/v1` — these are provider-facing, not client-facing.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/integrations/whatsapp/webhook` | — | Meta's webhook verification challenge (`WHATSAPP_VERIFY_TOKEN`). Must return a plain string — see `docs/COMMON_MISTAKES.md` #7 for why `ResponseBodyAdvice` must not wrap it. |
| POST | `/integrations/whatsapp/webhook` | HMAC (`WebhookSignatureVerifier`, `WHATSAPP_APP_SECRET`) | Inbound message/status callback. Verify signature before trusting payload. |
| GET | `/integrations/whatsapp/diagnostics` | JWT (`WHATSAPP_DIAGNOSTICS`) | Adapter health/config summary. |
| GET | `/integrations/whatsapp/diagnostics/ping` | JWT (`WHATSAPP_DIAGNOSTICS`) | Live connectivity check. |
| GET | `/integrations/whatsapp/diagnostics/test-send` | JWT (`WHATSAPP_DIAGNOSTICS`) | Send a test template message. |

## Security Notes

- All non-webhook responses are wrapped in `ApiResponse<T>` by `ApiResponseBodyAdvice` — never
  hand-wrap in a controller.
- Public (no-JWT) endpoints: `/api/v1/auth/{login,refresh}`, `/api/v1/parents/auth/{request-otp,
  verify-otp}`, and the WhatsApp webhook GET/POST — everything else requires a JWT plus, on mutating
  routes, the specific `@RequirePermission` named in `schoolbridge-modules.md`.
- OTP requests are rate-limited (`OtpRequestRateLimiter`, `RATE_LIMIT_*` config).
- The WhatsApp webhook is the one endpoint authenticated by signature instead of a bearer token —
  `WebhookSignatureVerifier` must run before the payload is trusted.
