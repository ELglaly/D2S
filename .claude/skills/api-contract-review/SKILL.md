---
name: api-contract-review
description: REST API contract review for SchoolBridge. Checks HTTP verb semantics, versioning, ApiResponse envelope, pagination, error codes, and i18n.
metadata:
  version: "2.0.0"
  domain: api
  triggers: API review, REST contract, endpoint design, HTTP semantics, versioning
  role: reviewer
  scope: api
  output-format: checklist
---

# API Contract Review Skill (SchoolBridge)

## HTTP Verb Semantics
- [ ] `GET` — read only, idempotent, no body
- [ ] `POST` — create resource or non-idempotent action (e.g., `/publish`, `/acknowledge`, `/recall`)
- [ ] `PUT` — full update of a resource (idempotent)
- [ ] `PATCH` — partial update (only changed fields)
- [ ] `DELETE` — remove resource, idempotent

## URL Design
- [ ] All endpoints start with `/api/v1/` (webhook/diagnostics endpoints under `/integrations/*` are
      the deliberate exception — provider-facing, not client-facing)
- [ ] Resource names are plural nouns: `/homework`, `/announcements`, `/attendance`
- [ ] **No `:verb` action paths** — real HTTP clients percent-encode `:` and the request 404s
      (`docs/COMMON_MISTAKES.md` #6, ADR-006). Use `/homework/{id}/publish`, not
      `/homework/{id}:publish`. `StudentController`'s `:bulk-import` is a known pre-existing
      exception, not a pattern to extend.
- [ ] Sub-resources reflect ownership: `/homework/{id}/recipients`, not
      `/homework-recipients?homeworkId=`

## Response Envelope
Every response is wrapped in `ApiResponse<T>` **automatically** by `ApiResponseBodyAdvice` — never
hand-wrap in a controller:
```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "meta": null
}
```
- [ ] No raw entity returned from controller
- [ ] Controller returns the domain response record or `ResponseEntity<T>`, not a pre-wrapped `ApiResponse<T>`
- [ ] Webhook/plain-string responses (e.g. the WhatsApp verification challenge GET) are excluded from
      wrapping — `ApiResponseBodyAdvice.supports()` checks both the Jackson converter type and the
      `com.schoolbridge.api` package (`docs/COMMON_MISTAKES.md` #7)

## HTTP Status Codes
- [ ] `200 OK` — GET, PUT, PATCH, DELETE success
- [ ] `201 Created` — POST that creates a resource
- [ ] `400 Bad Request` — validation error
- [ ] `401 Unauthorized` — missing or invalid JWT
- [ ] `403 Forbidden` — valid JWT but insufficient permission/row-ownership
- [ ] `404 Not Found` — resource does not exist (including "exists, wrong tenant")
- [ ] `409 Conflict` — duplicate resource, or an attachment not yet `CLEAN`
- [ ] `422 Unprocessable Entity` — business rule violation (not field validation)
- [ ] `500 Internal Server Error` — never returned with a user-facing detail message

## Pagination
- [ ] List endpoints support `page`/`size` (or an equivalent `Pageable` parameter)
- [ ] Default page size is reasonable; maximum page size is bounded

## Error Codes
- [ ] Error responses map through `ErrorType` (`NOT_FOUND`, `VALIDATION`, `AUTHENTICATION`,
      `AUTHORIZATION`, `CONFLICT`, `RATE_LIMIT`, `INTEGRATION`, `TENANT_SECURITY`, `INTERNAL`) —
      each pairs an `HttpStatus` with an i18n message key
- [ ] Consistent error type used for the same failure mode across endpoints

## i18n
- [ ] Every user-facing message key exists in **both** `messages_ar.properties` and
      `messages_en.properties` — parity is a hard requirement, not best-effort
- [ ] Error/validation messages resolve via the caller's locale

## Security on New Endpoints
- [ ] A genuinely public (no-JWT) endpoint is an explicit, deliberate addition to `SecurityConfig` —
      never a fallthrough
- [ ] Mutating endpoints carry `@RequirePermission`
- [ ] Own-resource check in service (e.g. `parentLinkedTo(studentId)`, `teacherTeaches(classId)`) —
      narrower than a permission, doesn't belong folded into `Permission` itself

## OpenAPI / SpringDoc
- [ ] `@Operation(summary = "...")` on all public controller methods
- [ ] `@Tag(name = "...")` on all controller classes
- [ ] DTOs have `@Schema` annotations for complex types
