package com.schoolbridge.api.homework;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Materialized per-(parent, student) recipient row for a homework item. Created at publish time by
 * {@code HomeworkServiceImpl.materializeRecipients}; mirrors {@code AttendanceAlertRecipient} with
 * the same quiet-hours deferral lifecycle.
 */
@Entity
@Table(name = "homework_recipients")
public class HomeworkRecipient extends TenantEntity {

  @Column(name = "homework_id", nullable = false, updatable = false)
  private UUID homeworkId;

  @Column(name = "parent_user_id", nullable = false, updatable = false)
  private UUID parentUserId;

  @Column(name = "student_id", nullable = false, updatable = false)
  private UUID studentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "delivery_status", nullable = false, length = 20)
  private HomeworkDeliveryStatus deliveryStatus;

  @Column(name = "message_id", length = 255)
  private String messageId;

  @Column(name = "last_error", length = 256)
  private String lastError;

  @Column(name = "deferred_until")
  private Instant deferredUntil;

  @Column(name = "acknowledged_at")
  private Instant acknowledgedAt;

  protected HomeworkRecipient() {}

  public HomeworkRecipient(UUID schoolId, UUID homeworkId, UUID parentUserId, UUID studentId) {
    super(schoolId);
    this.homeworkId = homeworkId;
    this.parentUserId = parentUserId;
    this.studentId = studentId;
    this.deliveryStatus = HomeworkDeliveryStatus.PENDING;
  }

  public void markDeferred(Instant until) {
    this.deliveryStatus = HomeworkDeliveryStatus.DEFERRED;
    this.deferredUntil = until;
    this.lastError = null;
  }

  public void releaseFromDeferral() {
    this.deliveryStatus = HomeworkDeliveryStatus.PENDING;
    this.deferredUntil = null;
  }

  public void markSent(String providerMessageId) {
    this.deliveryStatus = HomeworkDeliveryStatus.SENT;
    this.messageId = providerMessageId;
    this.lastError = null;
  }

  public void markFailed(String error) {
    this.deliveryStatus = HomeworkDeliveryStatus.FAILED;
    this.lastError = error == null ? null : truncate(error);
  }

  public void acknowledge(Instant when) {
    if (this.acknowledgedAt == null) {
      this.acknowledgedAt = when;
    }
  }

  public UUID getHomeworkId() {
    return homeworkId;
  }

  public UUID getParentUserId() {
    return parentUserId;
  }

  public UUID getStudentId() {
    return studentId;
  }

  public HomeworkDeliveryStatus getDeliveryStatus() {
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

  public Instant getAcknowledgedAt() {
    return acknowledgedAt;
  }

  private static String truncate(String value) {
    return value.length() <= 256 ? value : value.substring(0, 256);
  }
}
