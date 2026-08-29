package com.schoolbridge.api.integrations;

import java.util.List;

/**
 * Which outbound channel a {@link com.schoolbridge.api.integrations.NotificationDispatcher} used,
 * or may use.
 *
 * <p>The declaration order is not the send order â€” {@link #DEFAULT_ORDER} is, and a user's stored
 * preference overrides even that.
 */
public enum NotificationChannel {
  PUSH,
  WHATSAPP,
  SMS;

  /**
   * The order used when a user has expressed no preference: push first because it is the only free
   * channel and the only one that deep-links into the app, WhatsApp next because it is where these
   * families already are, SMS last because it costs the most per message and carries no formatting.
   */
  public static final List<NotificationChannel> DEFAULT_ORDER = List.of(PUSH, WHATSAPP, SMS);
}

