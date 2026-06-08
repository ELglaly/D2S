package com.schoolbridge.api.attendance;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Materialized per-(record, parent) outbound row. One row per linked parent of the absent (or late
 * / excused) student, created by {@code AttendanceAlertService} when the M8 consumer drains an
 * {@code attendance.*_alert} event from RabbitMQ.
 *
 * <p>The row carries delivery-tracking state — the future webhook M7 path can locate it by {@link
 * #getMessageId()} to update SENT/DELIVERED/READ without joining back to {@link AttendanceRecord}.
 * {@code deferredUntil} carries quiet-hours holds; {@code AttendanceSweeper} releases them once the
 * school-local quiet window closes.
 */
@Entity
@Table(name = "attendance_alert_recipients")
public class AttendanceAlertRecipient extends TenantEntity {

  @Column(name = "attendance_record_id", nullable = false, updatable = false)
  private UUID attendanceRecordId;

  @Column(name = "parent_user_id", nullable = false, updatable = false)
  private UUID parentUserId;

  @Column(name = "student_id", nullable = false, updatable = false)
  private UUID studentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "delivery_status", nullable = false, length = 20)
  private AttendanceAlertStatus deliveryStatus;

  @Column(name = "message_id", length = 255)
  private String messageId;

  @Column(name = "last_error", length = 256)
  private String lastError;

  @Column(name = "deferred_until")
  private Instant deferredUntil;

  protected AttendanceAlertRecipient() {}

  public AttendanceAlertRecipient(
      UUID schoolId, UUID attendanceRecordId, UUID parentUserId, UUID studentId) {
    super(schoolId);
    this.attendanceRecordId = attendanceRecordId;
    this.parentUserId = parentUserId;
    this.studentId = studentId;
    this.deliveryStatus = AttendanceAlertStatus.PENDING;
  }

  /** Called by the alert service when the per-school quiet-hours window applies. */
  public void markDeferred(Instant until) {
    this.deliveryStatus = AttendanceAlertStatus.DEFERRED;
    this.deferredUntil = until;
    this.lastError = null;
  }

  /**
   * Called by the sweeper before re-attempting a deferred row, so the dispatcher branch can run
   * without re-checking the deferred state.
   */
  public void releaseFromDeferral() {
    this.deliveryStatus = AttendanceAlertStatus.PENDING;
    this.deferredUntil = null;
  }

  /** Called once the M7 dispatcher returns a provider message id. */
  public void markSent(String providerMessageId) {
    this.deliveryStatus = AttendanceAlertStatus.SENT;
    this.messageId = providerMessageId;
    this.lastError = null;
  }

  /** Called when both the WhatsApp send and the SMS fallback fail. */
  public void markFailed(String error) {
    this.deliveryStatus = AttendanceAlertStatus.FAILED;
    this.lastError = error == null ? null : truncate(error);
  }

  public UUID getAttendanceRecordId() {
    return attendanceRecordId;
  }

  public UUID getParentUserId() {
    return parentUserId;
  }

  public UUID getStudentId() {
    return studentId;
  }

  public AttendanceAlertStatus getDeliveryStatus() {
    return deliveryStatus;
  }

  public String getMessageId() {
    return messageId;
  }

  public String getLastError() {
    return lastError;
  }

  public Instant getDeferredUntil() {
    return deferredUntil;
  }

  private static String truncate(String value) {
    return value.length() <= 256 ? value : value.substring(0, 256);
  }
}
