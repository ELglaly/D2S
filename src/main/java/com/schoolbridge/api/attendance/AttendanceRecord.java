package com.schoolbridge.api.attendance;

import com.schoolbridge.api.common.crypto.AesGcmAttributeConverter;
import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row per (school, student, class, date). Idempotent re-marks with the same status are no-ops;
 * a transition to a non-PRESENT status fires a status-specific outbox event consumed by {@code
 * AttendanceAlertConsumer} (M8 alert pipeline).
 *
 * <p>{@code parentResponse} is AES-GCM encrypted at rest via {@link AesGcmAttributeConverter}; the
 * column is 1024 bytes to keep symmetry with {@code User.name} / {@code Student.fullName}. {@code
 * alertSentAt} is set once <em>every</em> linked-parent {@code AttendanceAlertRecipient} has
 * reached a terminal state (SENT or FAILED) — that is what closes the NFR-P2 latency window.
 */
@Entity
@Table(name = "attendance_records")
public class AttendanceRecord extends TenantEntity {

  @Column(name = "student_id", nullable = false, updatable = false)
  private UUID studentId;

  @Column(name = "class_id", nullable = false, updatable = false)
  private UUID classId;

  @Column(nullable = false, updatable = false)
  private LocalDate date;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private AttendanceStatus status;

  @Column(name = "marked_by_user_id", nullable = false)
  private UUID markedByUserId;

  @Column(name = "marked_at", nullable = false)
  private Instant markedAt;

  // @Convert(converter = AesGcmAttributeConverter.class)
  @Column(name = "parent_response", length = 1024)
  private String parentResponse;

  @Column(name = "parent_responded_at")
  private Instant parentRespondedAt;

  @Column(name = "alert_sent_at")
  private Instant alertSentAt;

  protected AttendanceRecord() {}

  public AttendanceRecord(
      UUID schoolId,
      UUID studentId,
      UUID classId,
      LocalDate date,
      AttendanceStatus status,
      UUID markedByUserId,
      Instant markedAt) {
    super(schoolId);
    this.studentId = studentId;
    this.classId = classId;
    this.date = date;
    this.status = status;
    this.markedByUserId = markedByUserId;
    this.markedAt = markedAt;
  }

  /**
   * Applies a status transition. Callers must check {@link #getStatus()} first to skip same-status
   * no-ops; this method updates {@code markedBy} and {@code markedAt} unconditionally because a
   * second call by a different teacher is a meaningful re-mark.
   */
  public void retransition(AttendanceStatus newStatus, UUID markedByUserId, Instant markedAt) {
    this.status = newStatus;
    this.markedByUserId = markedByUserId;
    this.markedAt = markedAt;
    this.alertSentAt = null;
  }

  public void recordParentResponse(String response, Instant when) {
    this.parentResponse = response;
    this.parentRespondedAt = when;
  }

  /** Stamped by the alert pipeline once every linked-parent recipient reaches a terminal state. */
  public void markAlertSent(Instant when) {
    if (this.alertSentAt == null) {
      this.alertSentAt = when;
    }
  }

  public UUID getStudentId() {
    return studentId;
  }

  public UUID getClassId() {
    return classId;
  }

  public LocalDate getDate() {
    return date;
  }

  public AttendanceStatus getStatus() {
    return status;
  }

  public UUID getMarkedByUserId() {
    return markedByUserId;
  }

  public Instant getMarkedAt() {
    return markedAt;
  }

  public String getParentResponse() {
    return parentResponse;
  }

  public Instant getParentRespondedAt() {
    return parentRespondedAt;
  }

  public Instant getAlertSentAt() {
    return alertSentAt;
  }
}
