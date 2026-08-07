package com.schoolbridge.api.announcements;

import com.schoolbridge.api.announcements.enums.AnnouncementScope;
import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An announcement composed by a SCHOOL_ADMIN or a TEACHER. The {@code body} is AES-GCM encrypted at
 * rest. {@code scopeValue} carries the scope-specific target identifier as a string:
 *
 * <ul>
 *   <li>{@link AnnouncementScope#CLASS}: classId as a string.
 *   <li>{@link AnnouncementScope#GRADE}: grade-level string (e.g. "Grade 3").
 *   <li>{@link AnnouncementScope#SCHOOL} and {@link AnnouncementScope#CUSTOM}: null. For CUSTOM the
 *       materialized {@link AnnouncementRecipient} rows are the source of truth.
 * </ul>
 */
@Entity
@Table(name = "announcements")
public class Announcement extends TenantEntity {

  @Column(name = "sender_id", nullable = false, updatable = false)
  private UUID senderId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", nullable = false, length = 20, updatable = false)
  private AnnouncementScope scopeType;

  @Column(name = "scope_value", length = 100, updatable = false)
  private String scopeValue;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 8, updatable = false)
  private Language language;

  // @Convert(converter = AesGcmAttributeConverter.class)
  @Column(nullable = false, length = 8192, updatable = false)
  private String body;

  @Column(name = "attachment_key", length = 512, updatable = false)
  private String attachmentKey;

  @Column(name = "requires_ack", nullable = false, updatable = false)
  private boolean requiresAck;

  @Column(name = "scheduled_for", updatable = false)
  private Instant scheduledFor;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private AnnouncementStatus status;

  protected Announcement() {}

  public Announcement(
      UUID schoolId,
      UUID senderId,
      AnnouncementScope scopeType,
      String scopeValue,
      Language language,
      String body,
      String attachmentKey,
      boolean requiresAck,
      Instant scheduledFor,
      AnnouncementStatus initialStatus) {
    super(schoolId);
    this.senderId = senderId;
    this.scopeType = scopeType;
    this.scopeValue = scopeValue;
    this.language = language;
    this.body = body;
    this.attachmentKey = attachmentKey;
    this.requiresAck = requiresAck;
    this.scheduledFor = scheduledFor;
    this.status = initialStatus;
  }

  public void recall() {
    this.status = AnnouncementStatus.RECALLED;
  }

  /**
   * Moves a SCHEDULED announcement to SENT once its {@code scheduledFor} has arrived. Called only
   * by {@code AnnouncementScheduleSweeper}; a RECALLED announcement is deliberately left alone so
   * cancelling before the send time actually cancels it.
   */
  public void markSent() {
    if (this.status == AnnouncementStatus.SCHEDULED) {
      this.status = AnnouncementStatus.SENT;
    }
  }

  public UUID getSenderId() {
    return senderId;
  }

  public AnnouncementScope getScopeType() {
    return scopeType;
  }

  public String getScopeValue() {
    return scopeValue;
  }

  public Language getLanguage() {
    return language;
  }

  public String getBody() {
    return body;
  }

  public String getAttachmentKey() {
    return attachmentKey;
  }

  public boolean isRequiresAck() {
    return requiresAck;
  }

  public Instant getScheduledFor() {
    return scheduledFor;
  }

  public AnnouncementStatus getStatus() {
    return status;
  }
}
