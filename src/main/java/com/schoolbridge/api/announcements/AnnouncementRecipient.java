package com.schoolbridge.api.announcements;

import com.schoolbridge.api.announcements.enums.DeliveryStatus;
import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Materialized per-(parent, student) recipient row for an announcement. One row per child means a
 * parent of two children in the same class receives two recipient rows and acknowledges per child.
 *
 * <p>{@code messageId} is set by the M7 WhatsApp send adapter once a template send returns.
 */
@Entity
@Table(name = "announcement_recipients")
public class AnnouncementRecipient extends TenantEntity {

  @Column(name = "announcement_id", nullable = false, updatable = false)
  private UUID announcementId;

  @Column(name = "parent_user_id", nullable = false, updatable = false)
  private UUID parentUserId;

  @Column(name = "student_id", nullable = false, updatable = false)
  private UUID studentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "delivery_status", nullable = false, length = 20)
  private DeliveryStatus deliveryStatus;

  @Column(name = "acknowledged_at")
  private Instant acknowledgedAt;

  @Column(name = "message_id", length = 255)
  private String messageId;

  @Column(name = "deferred_until")
  private Instant deferredUntil;

  protected AnnouncementRecipient() {}

  public AnnouncementRecipient(
      UUID schoolId, UUID announcementId, UUID parentUserId, UUID studentId) {
    super(schoolId);
    this.announcementId = announcementId;
    this.parentUserId = parentUserId;
    this.studentId = studentId;
    this.deliveryStatus = DeliveryStatus.QUEUED;
  }

  public void acknowledge(Instant when) {
    if (this.acknowledgedAt == null) {
      this.acknowledgedAt = when;
    }
  }

  /** Called by the M7 dispatcher consumer once a send adapter returns a provider message id. */
  public void markSent(String providerMessageId) {
    this.deliveryStatus = DeliveryStatus.SENT;
    this.messageId = providerMessageId;
    this.deferredUntil = null;
  }

  /**
   * Held because the recipient is inside their quiet window. {@code AnnouncementDeferralSweeper}
   * releases it once {@code until} passes.
   */
  public void markDeferred(Instant until) {
    this.deliveryStatus = DeliveryStatus.DEFERRED;
    this.deferredUntil = until;
  }

  /** Back to QUEUED so the normal dispatch path can pick it up. */
  public void releaseFromDeferral() {
    this.deliveryStatus = DeliveryStatus.QUEUED;
    this.deferredUntil = null;
  }

  /**
   * The recipient opted out of announcements. Distinct from FAILED on purpose: nothing went wrong,
   * nothing should be retried, and a delivery report should not count this as a miss.
   */
  public void markSuppressed() {
    this.deliveryStatus = DeliveryStatus.SUPPRESSED;
    this.deferredUntil = null;
  }

  /**
   * Called by the M7 dispatcher consumer when both WhatsApp and the SMS fallback fail. {@code
   * messageId} stays null so the webhook never matches a FAILED row by id.
   */
  public void markFailed() {
    this.deliveryStatus = DeliveryStatus.FAILED;
  }

  /**
   * Called by the webhook on a delivery/read transition from Meta. Idempotent for the same status
   * (caller must also drop duplicates via the Redis key â€” see {@code
   * IntegrationsWhatsAppWebhookController}).
   */
  public void updateDeliveryStatus(DeliveryStatus newStatus) {
    this.deliveryStatus = newStatus;
  }

  public UUID getAnnouncementId() {
    return announcementId;
  }

  public UUID getParentUserId() {
    return parentUserId;
  }

  public UUID getStudentId() {
    return studentId;
  }

  public DeliveryStatus getDeliveryStatus() {
    return deliveryStatus;
  }

  public Instant getAcknowledgedAt() {
    return acknowledgedAt;
  }

  public String getMessageId() {
    return messageId;
  }

  public Instant getDeferredUntil() {
    return deferredUntil;
  }
}

