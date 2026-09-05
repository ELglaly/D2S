package com.schoolbridge.api.notifications;

/**
 * The kinds of outbound message a user can hold a preference about.
 *
 * <p>{@link #ATTENDANCE} is deliberately <b>not</b> mutable. An absence alert carries the NFR-P2
 * five-minute end-to-end SLA, and a parent who had muted it â€” or deferred it past a quiet window
 * â€” would simply not learn that their child is missing from class. The immutability is enforced
 * in {@link NotificationPreferenceService#resolve} rather than left to callers, and the write API
 * rejects an attempt to disable it instead of storing a row that lies about what will happen.
 *
 * <p>Authentication codes are not a category at all: {@code WhatsAppOtpDispatcher} bypasses this
 * whole mechanism, because a login code a user can mute is a support ticket, not a preference.
 */
public enum NotificationCategory {
  ANNOUNCEMENT,
  HOMEWORK,
  ATTENDANCE;

  /** False for the categories a user may not opt out of or defer. */
  public boolean isMutable() {
    return this != ATTENDANCE;
  }
}
