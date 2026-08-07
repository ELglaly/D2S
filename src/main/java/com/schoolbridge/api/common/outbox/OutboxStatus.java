package com.schoolbridge.api.common.outbox;

/**
 * Lifecycle of a transactional-outbox event.
 *
 * <p>{@code FAILED} is <b>retryable</b>, not terminal: the relay re-claims failed rows once {@code
 * next_attempt_at} passes and only gives up at {@link OutboxEvent#MAX_ATTEMPTS}, at which point the
 * row moves to {@code DEAD}. Treating a failure as terminal is what previously let one broker blip
 * silently drop an announcement.
 */
public enum OutboxStatus {
  PENDING,
  PUBLISHED,
  FAILED,
  DEAD
}
