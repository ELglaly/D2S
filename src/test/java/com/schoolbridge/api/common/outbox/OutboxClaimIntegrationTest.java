package com.schoolbridge.api.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises the relay's claim query against a real Postgres. The important part is that it runs at
 * all: {@code claimDue} is annotated {@code PESSIMISTIC_WRITE} with a {@code -2} lock timeout,
 * which Hibernate compiles to {@code FOR UPDATE SKIP LOCKED}. That combination is only validated by
 * the database — a typo in the hint fails here rather than silently degrading to a plain {@code FOR
 * UPDATE} and serialising every relay instance in production.
 */
@SpringBootTest
class OutboxClaimIntegrationTest extends AbstractIntegrationTest {

  @Autowired OutboxRepository repository;
  @Autowired TransactionTemplate tx;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> repository.deleteAll());
  }

  private OutboxEvent save(OutboxEvent event) {
    return tx.execute(s -> repository.save(event));
  }

  private static OutboxEvent event(String type) {
    return new OutboxEvent(UUID.randomUUID(), "Announcement", UUID.randomUUID(), type, "{}");
  }

  @Test
  void claimsDuePendingRows() {
    save(event("announcement.created"));

    List<OutboxEvent> claimed =
        tx.execute(s -> repository.claimDue(Instant.now(), PageRequest.of(0, 10)));

    assertThat(claimed).hasSize(1);
    assertThat(claimed.get(0).getEventType()).isEqualTo("announcement.created");
  }

  @Test
  void skipsRowsWhoseBackoffHasNotElapsed() {
    OutboxEvent event = event("attendance.absent");
    event.markFailed("broker down");
    save(event);

    List<OutboxEvent> tooEarly =
        tx.execute(s -> repository.claimDue(Instant.now(), PageRequest.of(0, 10)));
    assertThat(tooEarly).as("must wait out the backoff").isEmpty();

    List<OutboxEvent> later =
        tx.execute(
            s ->
                repository.claimDue(
                    Instant.now().plus(1, ChronoUnit.HOURS), PageRequest.of(0, 10)));
    assertThat(later).as("a FAILED row is retryable once its backoff elapses").hasSize(1);
  }

  @Test
  void neverClaimsDeadOrPublishedRows() {
    OutboxEvent dead = event("announcement.created");
    for (int i = 0; i < OutboxEvent.MAX_ATTEMPTS; i++) {
      dead.markFailed("poison");
    }
    save(dead);

    OutboxEvent published = event("announcement.created");
    published.markPublished();
    save(published);

    List<OutboxEvent> claimed =
        tx.execute(
            s ->
                repository.claimDue(Instant.now().plus(7, ChronoUnit.DAYS), PageRequest.of(0, 10)));

    assertThat(claimed).isEmpty();
    assertThat(repository.countByStatus(OutboxStatus.DEAD)).isEqualTo(1);
  }
}
