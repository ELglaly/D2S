# SchoolBridge Postman Artifacts

This directory contains importable Postman artifacts for the SchoolBridge API:

- `SchoolBridge.postman_collection.json`
- `SchoolBridge.local.postman_environment.json`

The collection was generated from the Spring Boot controllers and includes success, authentication,
authorization, validation, not-found, conflict, rate-limit, upload, and WhatsApp webhook scenarios.
Each request has a Postman test for the expected status code. JSON success responses also assert the
`{ data, meta }` envelope, while error responses assert the RFC 7807 `ProblemDetail` shape.

Import both files into Postman and select the `SchoolBridge Local` environment. Set these variables
before running protected flows:

- `superAdminToken`
- `schoolAdminToken`
- `teacherToken`
- `parentToken`
- `staffEmail` / `staffPassword`
- `baseUrl`

Some scenario requests are intentionally state-dependent. For example, duplicate `409` scenarios
need the matching create request to be run first, and WhatsApp webhook success requires a valid
`X-Hub-Signature-256` HMAC for the exact raw body.

The Postman cloud connector could not create the remote workspace because its configured API key
returned `401 Invalid API Key`. Once the key is fixed, import these artifacts into a `SchoolBridge API`
workspace or use the connector to upload them.

To regenerate:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\postman\generate-schoolbridge-postman.ps1
```
