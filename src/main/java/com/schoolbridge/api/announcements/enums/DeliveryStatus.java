package com.schoolbridge.api.announcements.enums;

/**
 * Per-recipient delivery state.
 *
 * <p>{@link #QUEUED} through {@link #FAILED} are the provider-facing lifecycle: M6 writes QUEUED
 * and the WhatsApp webhook consumer (M7) plus the parent acknowledgement flow advance it from
 * there.
 *
 * <p>{@link #DEFERRED} and {@link #SUPPRESSED} are decided by the recipient's own notification
 * preferences before any provider is involved, so neither ever carries a {@code messageId} and
 * neither can be reached by a delivery callback.
 */
public enum DeliveryStatus {
  QUEUED,
  /**
   * Held until the recipient's quiet window closes; released by {@code
   * AnnouncementDeferralSweeper}.
   */
  DEFERRED,
  /** The recipient opted out of announcements. Terminal â€” nothing will retry it. */
  SUPPRESSED,
  SENT,
  DELIVERED,
  READ,
  FAILED
}
