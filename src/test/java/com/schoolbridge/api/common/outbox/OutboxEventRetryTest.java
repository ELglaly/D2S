package com.schoolbridge.api.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The outbox retry state machine. Guards the defect this replaced: {@code markFailed} used to set a
 * terminal FAILED status, so a single broker blip permanently dropped whichever announcement or
 * absence alert was in flight, with no retry and nothing to alert on.
 */
class OutboxEventRetryTest {

  private static OutboxEvent event() {
    return new OutboxEvent(UUID.randomUUID(), "Announcement", UUID.randomUUID(), "created", "{}");
  }

  @Test
  void newEventIsImmediatelyClaimable() {
    OutboxEvent event = event();
    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(event.getNextAttemptAt()).isBeforeOrEqualTo(Instant.now());
  }

  @Test
  void firstFailureIsRetryableNotTerminal() {
    OutboxEvent event = event();
    event.markFailed("broker unavailable");

    assertThat(event.getStatus())
        .as("a single failure must never be terminal")
        .isEqualTo(OutboxStatus.FAILED);
    assertThat(event.getAttempts()).isEqualTo(1);
    assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
    assertThat(event.getLastError()).isEqualTo("broker unavailable");
  }

  @Test
  void backoffGrowsWithEachAttempt() {
    OutboxEvent event = event();
    event.markFailed("boom");
    Instant afterFirst = event.getNextAttemptAt();
    event.markFailed("boom");
    Instant afterSecond = event.getNextAttemptAt();

    assertThat(afterSecond).as("backoff must widen, not repeat").isAfter(afterFirst);
  }

  @Test
  void goesDeadAtTheAttemptCeilingAndStopsBeingClaimable() {
    OutboxEvent event = event();
    for (int i = 0; i < OutboxEvent.MAX_ATTEMPTS; i++) {
      event.markFailed("boom");
    }

    assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
    assertThat(event.getAttempts()).isEqualTo(OutboxEvent.MAX_ATTEMPTS);
    assertThat(event.getNextAttemptAt())
        .as("a DEAD row must not be re-claimed — a poison payload cannot spin the relay")
        .isNull();
    assertThat(event.getLastError()).as("evidence stays for the operator").isNotNull();
  }

  @Test
  void publishClearsTheRetrySchedule() {
    OutboxEvent event = event();
    event.markFailed("transient");
    event.markPublished();

    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    assertThat(event.getNextAttemptAt()).isNull();
    assertThat(event.getLastError()).isNull();
  }
}
