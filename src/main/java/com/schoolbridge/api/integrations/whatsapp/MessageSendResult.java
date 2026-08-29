package com.schoolbridge.api.integrations.whatsapp;

/**
 * Outcome of a single outbound message send.
 *
 * <p>{@code messageId} is the provider's id (Meta's {@code messages[0].id}) and is non-null on
 * success; null on a hard failure that did not result in a queued message. {@code accepted} is true
 * when the provider acknowledged the send (it does NOT mean delivered â€” delivery transitions arrive
 * later via webhook).
 */
public record MessageSendResult(String messageId, boolean accepted) {

  public static MessageSendResult accepted(String messageId) {
    return new MessageSendResult(messageId, true);
  }

  public static MessageSendResult rejected() {
    return new MessageSendResult(null, false);
  }
}

