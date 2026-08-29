package com.schoolbridge.api.common.outbox;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls due outbox rows and publishes them via {@link OutboxPublisher}. Disabled by default;
 * enabled once a broker publisher bean exists.
 *
 * <p>Two properties matter here and both were previously missing. Rows are claimed with {@code FOR
 * UPDATE SKIP LOCKED} ({@link OutboxRepository#claimDue}), so running more than one instance splits
 * the work instead of double-publishing every event. And a failure is a <b>retry</b>, not a
 * tombstone â€” {@link OutboxEvent#markFailed} schedules exponential backoff and only parks the row
 * as {@code DEAD} after {@link OutboxEvent#MAX_ATTEMPTS}. Before that, one RabbitMQ blip
 * permanently dropped whichever announcement or absence alert happened to be in flight.
 */
@Component
@ConditionalOnProperty(name = "schoolbridge.outbox.relay.enabled", havingValue = "true")
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxRepository repository;
  private final OutboxPublisher publisher;
  private final int batchSize;

  public OutboxRelay(
      OutboxRepository repository,
      OutboxPublisher publisher,
      @Value("${schoolbridge.outbox.relay.batch-size:100}") int batchSize) {
    this.repository = repository;
    this.publisher = publisher;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${schoolbridge.outbox.relay.poll-interval:5s}")
  @Transactional
  public void publishPending() {
    List<OutboxEvent> batch = repository.claimDue(Instant.now(), PageRequest.of(0, batchSize));
    for (OutboxEvent event : batch) {
      try {
        publisher.publish(event);
        event.markPublished();
      } catch (RuntimeException ex) {
        event.markFailed(ex.getMessage());
        if (event.getStatus() == OutboxStatus.DEAD) {
          // Terminal: nothing will retry this. Alert on it â€” a DEAD row is an undelivered
          // announcement or absence alert that a parent will never receive.
          log.error(
              "outbox_dead id={} type={} school={} attempts={}",
              event.getId(),
              event.getEventType(),
              event.getSchoolId(),
              event.getAttempts(),
              ex);
        } else {
          log.warn(
              "outbox_publish_failed id={} type={} attempts={} retry_at={}",
              event.getId(),
              event.getEventType(),
              event.getAttempts(),
              event.getNextAttemptAt(),
              ex);
        }
      }
    }
  }
}

