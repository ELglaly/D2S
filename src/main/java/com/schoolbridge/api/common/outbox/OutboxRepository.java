package com.schoolbridge.api.common.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

  List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

  /**
   * Claims a batch of due events for this relay instance only.
   *
   * <p>{@code PESSIMISTIC_WRITE} + {@code javax.persistence.lock.timeout = -2} compiles to {@code
   * SELECT â€¦ FOR UPDATE SKIP LOCKED}. Without {@code SKIP LOCKED}, two app instances polling the
   * same table either publish the same event twice (no lock) or serialise behind each other (plain
   * {@code FOR UPDATE}); with it, each instance takes a disjoint slice and the relay scales
   * horizontally. This is what makes running more than one pod safe.
   *
   * <p>{@code FAILED} is included because failures are retryable â€” the row is re-claimed once its
   * backoff has elapsed. {@code DEAD} rows are excluded and stay put for an operator.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
  @Query(
      "select e from OutboxEvent e "
          + "where e.status in (com.schoolbridge.api.common.outbox.OutboxStatus.PENDING, "
          + "                   com.schoolbridge.api.common.outbox.OutboxStatus.FAILED) "
          + "and e.nextAttemptAt <= :now "
          + "order by e.nextAttemptAt asc")
  List<OutboxEvent> claimDue(@Param("now") Instant now, Pageable pageable);

  /** Backs the {@code outbox_dead} gauge â€” a non-zero value means delivery was permanently lost. */
  long countByStatus(OutboxStatus status);
}

