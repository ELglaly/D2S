package com.schoolbridge.api.attachments;

/** Outcome of an anti-virus scan, recorded on the attachment row for audit. */
public enum AvResult {

  /** Scanner ran and found nothing. */
  CLEAN,

  /** Scanner matched a signature. The matched name is stored alongside. */
  INFECTED,

  /**
   * Scanning is disabled for this environment. Distinct from {@link #CLEAN} on purpose: "nobody
   * looked" and "someone looked and it was fine" must not be the same record. The prod profile
   * refuses to start in this configuration.
   */
  SKIPPED
}
