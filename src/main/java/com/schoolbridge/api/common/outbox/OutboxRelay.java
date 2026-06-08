package com.schoolbridge.api.common.outbox;

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
 * Polls PENDING outbox rows and publishes them via {@link OutboxPublisher}, marking each
 * PUBLISHED/FAILED. Disabled by default; enabled once a broker publisher bean exists.
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
    List<OutboxEvent> batch =
        repository.findByStatusOrderByCreatedAtAsc(
            OutboxStatus.PENDING, PageRequest.of(0, batchSize));
    for (OutboxEvent event : batch) {
      try {
        publisher.publish(event);
        event.markPublished();
      } catch (RuntimeException ex) {
        log.error("outbox_publish_failed id={} type={}", event.getId(), event.getEventType(), ex);
        event.markFailed(ex.getMessage());
      }
    }
  }
}
