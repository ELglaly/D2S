package com.schoolbridge.api.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A domain event persisted in the same transaction as the mutation that produced it. A relay later
 * publishes PENDING rows to RabbitMQ, giving exactly-once-from-DB delivery. Not tenant-filtered so
 * the cross-tenant relay can read every school's events.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.TIME)
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "school_id", nullable = false, updatable = false)
  private UUID schoolId;

  @Column(name = "aggregate_type", nullable = false, length = 100)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private OutboxStatus status = OutboxStatus.PENDING;

  @Column(nullable = false)
  private int attempts = 0;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /**
   * When this row becomes claimable again. Set on insert so the first delivery is immediate, then
   * pushed out by {@link #markFailed} on each failure. Null for terminal rows.
   */
  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt = Instant.now();

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "last_error", length = 2000)
  private String lastError;

  /** Deliveries attempted before a row is parked as {@link OutboxStatus#DEAD} for an operator. */
  public static final int MAX_ATTEMPTS = 8;

  private static final Duration BASE_BACKOFF = Duration.ofSeconds(10);
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

  protected OutboxEvent() {}

  public OutboxEvent(
      UUID schoolId, String aggregateType, UUID aggregateId, String eventType, String payload) {
    this.schoolId = schoolId;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.payload = payload;
  }

  public void markPublished() {
    this.status = OutboxStatus.PUBLISHED;
    this.publishedAt = Instant.now();
    this.nextAttemptAt = null;
    this.lastError = null;
  }

  /**
   * Records a failed delivery and schedules the retry. Exponential backoff (10s, 20s, 40s, â€¦)
   * capped at 30 minutes; after {@link #MAX_ATTEMPTS} the row goes {@code DEAD} and stops being
   * claimed, so a poison payload cannot spin the relay forever while still leaving the evidence in
   * place for an operator.
   */
  public void markFailed(String error) {
    this.attempts += 1;
    this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
    if (this.attempts >= MAX_ATTEMPTS) {
      this.status = OutboxStatus.DEAD;
      this.nextAttemptAt = null;
      return;
    }
    this.status = OutboxStatus.FAILED;
    long backoffSeconds =
        Math.min(BASE_BACKOFF.getSeconds() << (this.attempts - 1), MAX_BACKOFF.getSeconds());
    this.nextAttemptAt = Instant.now().plusSeconds(backoffSeconds);
  }

  public UUID getId() {
    return id;
  }

  public UUID getSchoolId() {
    return schoolId;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getPayload() {
    return payload;
  }

  public OutboxStatus getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public String getLastError() {
    return lastError;
  }
}
