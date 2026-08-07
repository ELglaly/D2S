# ADR-006: Slash-style action paths, not AIP colon-verb paths

**Status:** Accepted

## Context

An early spec proposed Google-AIP-style action-verb paths
(`POST /api/v1/attendance:mark`, `POST /api/v1/schools/{id}:suspend`).

## Decision

Use slash-style action paths instead: `/attendance/mark`,
`/schools/{id}/suspend`. `AttendanceController` is the canonical example.

## Why

RestAssured's URL builder (and OkHttp's/the JDK `HttpClient`'s/curl's
default alias/Postman's path-variable input) percent-encodes `:` inside a
path segment to `%3A`. Spring's handler mapping does not match a `%3A`-
encoded segment against a `:mark`-style `@PostMapping`, so the request falls
through to the static-resource handler → `NoResourceFoundException` → the
global exception handler turns it into a 500. The server *can* register the
colon mapping and would respond correctly to a literal, unencoded `:` — but
real HTTP clients essentially never send one.

## Consequences

- If a future spec insists on the AIP colon form, the fallback is to
  register both path shapes on the controller
  (`@PostMapping({"/mark", ":mark"})`) and document the deviation in that
  module's handoff doc — not to rely on the colon form alone.
- Every new "verb-shaped" endpoint (mark, suspend, recall, acknowledge,
  publish, …) uses the slash form by default; this is now the reviewable
  default, not a case-by-case judgment call.
