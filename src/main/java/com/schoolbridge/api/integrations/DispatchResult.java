package com.schoolbridge.api.integrations;

/**
 * Outcome of a single {@link NotificationDispatcher#dispatch} call. {@code messageId} is the
 * provider's id (Meta's {@code messages[0].id} or the SMS provider's reference) on success, null
 * otherwise.
 */
public record DispatchResult(NotificationChannel channel, String messageId, boolean accepted) {

  public boolean isFailure() {
    return !accepted;
  }
}
