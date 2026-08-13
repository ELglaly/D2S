# Plan — File / Attachment Pipeline (P0 item 10)

> Status: in progress. Companion to `docs/P0_REMEDIATION.md`, which deferred this
> item to its own gated build. Source finding: `docs/PLATFORM_REVIEW.md` §2.2 and
> the §14 security table row *"No file upload path, therefore no AV scanning, MIME
> allow-list, or real size enforcement"*.

## 1. Why

`attachment_key` exists on `homework_items` and `announcements` as a
`VARCHAR(512)` that nothing writes and nothing resolves. `HomeworkItem`'s own
javadoc admits it: *"an opaque string in M9 — S3 wiring deferred to M14."* A
teacher cannot attach the photo of the homework page; a school cannot send the
PDF circular. Per `PLATFORM_REVIEW.md` §2 this is the **second-most valuable
missing feature** in the product, behind teacher↔parent messaging.

The only file-related control in the codebase today is
`spring.servlet.multipart.max-file-size: 5MB`, and no endpoint consumes
multipart, so it constrains nothing.

MinIO already sits in `docker-compose.yml` with no client in the pom.

## 2. Architecture

**User bytes never transit the API origin.** Not for upload, not for download.
This is the single decision the rest of the design follows from:

- Serving user-supplied files from the API's own origin means a stored-XSS or
  content-sniffing bug executes in the API's security origin, against a session
  that is already authenticated.
- Proxying multi-megabyte uploads through a servlet thread makes an unbounded
  upload an availability problem for every other request.

So: **presigned PUT in, presigned GET out**, S3-compatible object storage (MinIO
locally, S3/R2 in production — one endpoint override apart), AWS SDK v2.

### 2.1 The two-phase upload, and why it has to be two phases

Presigned PUT means the API does not see the bytes at upload time, so it cannot
sniff content at upload time. The pipeline is therefore:

```
POST /attachments                  -> row PENDING, returns { id, uploadUrl, key }
   client PUTs bytes directly to object storage
POST /attachments/{id}/complete    -> HeadObject (real size, stored type)
                                      ranged GET of the first 4 KiB -> magic-byte sniff
                                      AV scan (streamed storage -> clamd, server side)
                                      -> CLEAN | REJECTED | INFECTED
GET  /attachments/{id}/download    -> presigned GET, CLEAN only, short TTL
```

Status machine, enforced in the entity (no setter reaches a terminal state
backwards):

```
PENDING ──complete──> UPLOADED ──scan──> CLEAN
   │                     │                 │
   │                     ├──> REJECTED  (MIME/size/checksum failure)
   │                     └──> INFECTED  (AV positive)
   └──sweeper (abandoned)──> deleted
```

A presigned download URL is issued for `CLEAN` and nothing else. `INFECTED`
objects are deleted from storage immediately; the row is kept as a record.

### 2.2 Size enforcement under presigned PUT

A presigned **PUT** cannot carry a `content-length-range` condition — that is a
presigned **POST** form-policy feature. The equivalent for PUT is to *sign*
`Content-Length` as a signed header: the client declares the byte count in
`POST /attachments`, the API validates it against the configured maximum and
signs the PUT for exactly that length. Storage rejects any request whose actual
length differs from the signed value, so the cap is enforced by the object store,
not by client good behaviour.

`complete` re-reads the real object size via `HeadObject` and rejects on
mismatch, so a storage backend with laxer signature semantics than S3 still
cannot smuggle a larger object past the cap.

### 2.3 Keys are server-generated

```
{schoolId}/{yyyy}/{MM}/{attachmentId}
```

The client never supplies, and never influences, the key. A client-influenced
key is a cross-tenant write primitive (`../` or simply another school's UUID
prefix) and it would make the per-tenant prefix decorative. The presigned PUT is
bound to the exact key, so a client holding a URL can write that one object and
nothing else.

The `schoolId` prefix exists so that bucket-level policy, lifecycle rules, and
per-tenant export/erasure are all expressible without a database read.

### 2.4 MIME allow-list, sniffed not trusted

The declared `Content-Type` is a client-supplied string. The allow-list is
checked against **magic bytes read from stored object**, and the declared type
must agree with the sniffed type. Allowed: `image/jpeg`, `image/png`,
`image/webp`, `application/pdf`. Anything else is `REJECTED` and the object is
deleted.

Sniffing reads the first 4 KiB via a ranged GET, not the whole object — a
signature check needs at most the first few bytes and a bounded read cannot be
turned into a memory-exhaustion vector by a large upload.

### 2.5 AV

`AvScanner` interface with two implementations:

- `ClamAvScanner` — speaks the clamd `INSTREAM` wire protocol over TCP, streaming
  the object from storage through the socket in chunks, never buffering the whole
  file. This is the production path.
- `DisabledAvScanner` — returns `SKIPPED`. The default, so local development and
  the test suite do not need a ~250 MB ClamAV image and its signature-database
  startup on every `mvn verify`.

Shipping "disabled by default" is only safe if production cannot *accidentally*
inherit it, so `AvStartupValidator` (`@Profile("prod")`) refuses to start the
context when `schoolbridge.storage.av.enabled` is false — the same fail-loud
pattern `RlsStartupValidator` already uses for the RLS role split, for the same
reason: a security control that silently does nothing is worse than one that is
absent, because it is believed.

The clamd protocol implementation is unit-tested against a fake socket server;
the real ClamAV container is not in the default suite.

### 2.6 Tenancy

`attachments` is a tenant-owned table: `TenantEntity`, `school_id`, a `findById`
JPQL override (ADR-002 / `COMMON_MISTAKES.md` §1), and an RLS policy in the same
shape as changelog 017. Both layers, as everywhere else — the Hibernate filter
for ordinary queries, RLS as the line an application bug cannot cross.

## 3. Phases

### Phase 0 — dependencies and configuration
- `pom.xml`: `software.amazon.awssdk:bom:2.31.78` (import scope) + `s3`;
  `org.testcontainers:minio` (test, BOM-managed).
- `StorageProperties` record under `schoolbridge.storage` — endpoint, region,
  bucket, credentials (env-only, **no defaults**), path-style flag, max bytes,
  presign TTLs, allowed content types, retention days, AV host/port/enabled.
- Blocks in `application.yml` / `-local.yml` / `-prod.yml` / `application-test.yml`,
  and `.env.example`.

Credentials follow the `AES_KEY` precedent: no production default, env only.

### Phase 1 — changelog `018-attachments.sql`
`attachments` table. FKs to `schools(id)` and `users(id)` both
`ON DELETE CASCADE` (`COMMON_MISTAKES.md` §8 — existing tests' `deleteAll()`
teardown breaks otherwise). Unique index on `storage_key`. Indexes for the
sweeper's two queries (abandoned, and past-retention). RLS policy identical in
shape to 017, plus the master-changelog include.

### Phase 2 — domain
`Attachment` (extends `TenantEntity`), `AttachmentStatus`,
`AttachmentRepository` with the `findById` override and the sweeper queries.

### Phase 3 — object storage adapter
`ObjectStorage` interface + `S3ObjectStorage`: `presignPut`, `presignGet`,
`head`, `readRange`, `openStream`, `delete`. Key generation lives here so there
is exactly one place that decides what a key looks like.

### Phase 4 — content inspection
`ContentTypeSniffer` — magic-byte detection for the allow-list, JPEG/PNG/WebP/PDF,
returning `Optional<String>`; unknown ⇒ reject.

### Phase 5 — AV
`AvScanner`, `AvScanResult`, `ClamAvScanner`, `DisabledAvScanner`,
`AvStartupValidator`.

### Phase 6 — service and controller
`AttachmentService` / `AttachmentServiceImpl` / `AttachmentController`:
`create`, `complete`, `download`, `delete`. New `Permission` values
(`ATTACHMENT_UPLOAD`, `ATTACHMENT_READ`, `ATTACHMENT_DELETE`) seeded in the
migration alongside the existing catalog. Audit on upload and delete. All
user-facing messages through `MessageResolver`, `ar` + `en`.

Parents need `download` for attachments on homework/announcements addressed to
them; row-ownership stays a `@PreAuthorize` alongside the permission gate, per
ADR-006.

### Phase 7 — retention and orphans
`AttachmentSweeper`: deletes rows still `PENDING`/`UPLOADED` past the abandonment
window (client got a URL and vanished — the object may exist and is otherwise
unreferenced forever), and objects past `retention-days`. Disabled in the test
profile, like the other sweepers.

### Phase 8 — linkage
Today `attachmentKey` is a free string on homework and announcements, so a
successful upload still cannot be attached to anything — the pipeline would be
half a feature. `attachmentKey` becomes a reference validated on create/update:
it must resolve to a `CLEAN` attachment belonging to this school. Two existing
tests pass literal strings there and are updated.

### Phase 9 — tests, docs, green build
Integration tests against a MinIO Testcontainer:

1. Full round trip — create → PUT to the presigned URL → complete → download URL
   returns the bytes.
2. Declared/sniffed mismatch (`.pdf` name, PNG magic, `application/pdf` declared)
   ⇒ `REJECTED`, object deleted.
3. Disallowed type ⇒ `REJECTED`.
4. Over-cap declared size ⇒ 422 at create, no row.
5. Download before `complete`, and download of a `REJECTED` row ⇒ 409/404, no URL.
6. Cross-tenant: school B cannot read, complete, download or delete school A's
   attachment.
7. Keys carry the correct `schoolId` prefix.

Unit tests: sniffer table-driven; clamd `INSTREAM` framing against a fake socket
(clean and `stream: Eicar-Test-Signature FOUND`).

Docs: `RUNBOOK.md` (bucket + lifecycle + ClamAV deployment + the new env vars),
`PLATFORM_REVIEW.md` §14 row 10, `P0_REMEDIATION.md`, `.claude/CLAUDE.md` module
map, `docs/ARCHITECTURE.md`.

## 4. Verification

- `mvn -B -ntp verify` green — Spotless and SpotBugs are hard gates.
- Test count rises from 361 by the cases above.
- Manual against compose MinIO: upload a real JPEG end to end; confirm the object
  lands under `{schoolId}/…`; confirm a renamed `.exe` is rejected.

## 5. Out of scope

- **Image transcoding / thumbnailing.** Wanted, but it is a second pipeline with
  its own resource limits (decompression bombs), not part of "can a teacher
  attach a photo".
- **Per-school storage quotas.** The global size cap and retention window bound
  the damage; a quota needs a metering story of its own.
- **Virus re-scanning on signature updates.** Standard practice for long-lived
  stores; needs a re-scan queue.
- **Direct browser rendering / inline disposition.** Downloads are presigned
  attachments; inline rendering is a CSP conversation.
- **P0 item 11** (notification preferences + quiet hours) — its own gated build.
