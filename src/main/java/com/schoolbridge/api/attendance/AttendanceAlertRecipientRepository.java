package com.schoolbridge.api.attendance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link AttendanceAlertRecipient}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL â€” see {@code UserRepository} for rationale.
 * The webhook lookup {@link #findByMessageId} is intentionally global (no tenant binding) â€” Meta
 * only echoes statuses for message ids we issued, mirroring M7's announcement-webhook pattern.
 */
public interface AttendanceAlertRecipientRepository
    extends JpaRepository<AttendanceAlertRecipient, UUID> {

  @Override
  @Query("select r from AttendanceAlertRecipient r where r.id = :id")
  Optional<AttendanceAlertRecipient> findById(@Param("id") UUID id);

  List<AttendanceAlertRecipient> findAllByAttendanceRecordId(UUID attendanceRecordId);

  boolean existsByAttendanceRecordIdAndParentUserId(UUID attendanceRecordId, UUID parentUserId);

  /** Released by {@code AttendanceSweeper} once the per-school quiet window has closed. */
  @Query(
      "select r from AttendanceAlertRecipient r "
          + "where r.deliveryStatus = com.schoolbridge.api.attendance.AttendanceAlertStatus.DEFERRED "
          + "  and r.deferredUntil <= :now "
          + "  and r.messageId is null")
  List<AttendanceAlertRecipient> findDeferredReadyToDispatch(@Param("now") Instant now);

  /** Webhook lookup â€” see class doc. */
  @Query("select r from AttendanceAlertRecipient r where r.messageId = :messageId")
  Optional<AttendanceAlertRecipient> findByMessageId(@Param("messageId") String messageId);
}

