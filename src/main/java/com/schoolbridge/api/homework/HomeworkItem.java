package com.schoolbridge.api.homework;

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
 * A homework item assigned by a TEACHER (or SCHOOL_ADMIN) to a class. {@code description} is
 * AES-GCM encrypted at rest. {@code attachmentKey} is an opaque string in M9 â€” S3 wiring deferred
 * to M14. {@code reminderSentAt} is stamped immediately before the per-recipient reminder loop so
 * that the daily SETNX dedup and re-sweep cannot re-fire the same item.
 */
@Entity
@Table(name = "homework_items")
public class HomeworkItem extends TenantEntity {

  @Column(name = "class_id", nullable = false, updatable = false)
  private UUID classId;

  @Column(name = "teacher_id", nullable = false, updatable = false)
  private UUID teacherId;

  @Column(nullable = false, length = 200)
  private String subject;

  // @Convert(converter = AesGcmAttributeConverter.class)
  @Column(nullable = false, length = 8192)
  private String description;

  @Column(name = "attachment_key", length = 512)
  private String attachmentKey;

  @Column(name = "due_date", nullable = false)
  private LocalDate dueDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private HomeworkStatus status;

  @Column(name = "requires_ack", nullable = false)
  private boolean requiresAck;

  @Column(name = "reminder_sent_at")
  private Instant reminderSentAt;

  protected HomeworkItem() {}

  public HomeworkItem(
      UUID schoolId,
      UUID classId,
      UUID teacherId,
      String subject,
      String description,
      String attachmentKey,
      LocalDate dueDate,
      boolean requiresAck,
      HomeworkStatus initialStatus) {
    super(schoolId);
    this.classId = classId;
    this.teacherId = teacherId;
    this.subject = subject;
    this.description = description;
    this.attachmentKey = attachmentKey;
    this.dueDate = dueDate;
    this.requiresAck = requiresAck;
    this.status = initialStatus;
  }

  public void publish() {
    this.status = HomeworkStatus.PUBLISHED;
  }

  public void archive() {
    this.status = HomeworkStatus.ARCHIVED;
  }

  public void update(String subject, String description, String attachmentKey, LocalDate dueDate) {
    this.subject = subject;
    this.description = description;
    this.attachmentKey = attachmentKey;
    this.dueDate = dueDate;
  }

  /** Stamped immediately before the reminder dispatch loop so the sweeper does not re-fire. */
  public void markReminderSent(Instant when) {
    if (this.reminderSentAt == null) {
      this.reminderSentAt = when;
    }
  }

  public UUID getClassId() {
    return classId;
  }

  public UUID getTeacherId() {
    return teacherId;
  }

  public String getSubject() {
    return subject;
  }

  public String getDescription() {
    return description;
  }

  public String getAttachmentKey() {
    return attachmentKey;
  }

  public LocalDate getDueDate() {
    return dueDate;
  }

  public HomeworkStatus getStatus() {
    return status;
  }

  public boolean isRequiresAck() {
    return requiresAck;
  }

  public Instant getReminderSentAt() {
    return reminderSentAt;
  }
}
