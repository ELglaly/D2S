package com.schoolbridge.api.attachments;

/**
 * Lifecycle of an attachment. Only {@link #CLEAN} is downloadable.
 *
 * <pre>
 * PENDING ──complete──&gt; UPLOADED ──scan──&gt; CLEAN
 *    │                      │                 │
 *    │                      ├──&gt; REJECTED  (size / MIME / checksum)
 *    │                      └──&gt; INFECTED  (AV positive)
 *    └── swept when abandoned
 * </pre>
 *
 * <p>{@code PENDING} means an upload URL was issued and the bytes may or may not exist yet — the
 * API is not told when the client's PUT lands, so nothing but the client calling {@code complete}
 * distinguishes "still uploading" from "gave up". That ambiguity is why the sweeper deletes
 * long-abandoned rows rather than trying to reconcile them.
 */
public enum AttachmentStatus {

  /** Upload URL issued; the object may not exist yet. */
  PENDING,

  /** Bytes are present and their real size is known; inspection has not finished. */
  UPLOADED,

  /** Inspected, scanned, and safe to hand back through a presigned GET. */
  CLEAN,

  /** Failed size, MIME allow-list, or declared-vs-sniffed agreement. Object deleted. */
  REJECTED,

  /** AV reported a signature match. Object deleted; the row is kept as a record. */
  INFECTED;

  /** True only for the one state whose bytes may be served back to a caller. */
  public boolean isDownloadable() {
    return this == CLEAN;
  }

  /** True once the object has been inspected — no further transition is possible. */
  public boolean isTerminal() {
    return this == CLEAN || this == REJECTED || this == INFECTED;
  }
}
