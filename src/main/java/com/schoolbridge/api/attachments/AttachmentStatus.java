package com.schoolbridge.api.attachments;

/**
 * Lifecycle of an attachment. Only {@link #CLEAN} is downloadable.
 *
 * <pre>
 * PENDING â”€â”€completeâ”€â”€&gt; UPLOADED â”€â”€scanâ”€â”€&gt; CLEAN
 *    â”‚                      â”‚                 â”‚
 *    â”‚                      â”œâ”€â”€&gt; REJECTED  (size / MIME / checksum)
 *    â”‚                      â””â”€â”€&gt; INFECTED  (AV positive)
 *    â””â”€â”€ swept when abandoned
 * </pre>
 *
 * <p>{@code PENDING} means an upload URL was issued and the bytes may or may not exist yet â€” the
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

  /** True once the object has been inspected â€” no further transition is possible. */
  public boolean isTerminal() {
    return this == CLEAN || this == REJECTED || this == INFECTED;
  }
}

