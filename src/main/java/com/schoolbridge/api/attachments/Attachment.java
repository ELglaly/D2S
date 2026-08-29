package com.schoolbridge.api.attachments;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for one uploaded file. The bytes live in object storage under {@link #storageKey} and
 * never pass through this API â€” see {@code docs/PLAN_FILE_UPLOAD.md}.
 *
 * <p>Two pairs of fields exist because the client's claims and the stored reality are different
 * things and the difference is the control: {@code declaredContentType} / {@code declaredSizeBytes}
 * are what the client said when it asked for an upload URL, {@code contentType} / {@code sizeBytes}
 * are what the object actually turned out to be. Only the latter pair is trusted; a disagreement is
 * a rejection.
 *
 * <p>State transitions are methods rather than a setter, so a terminal status cannot be walked
 * backwards into a downloadable one.
 */
@Entity
@Table(name = "attachments")
public class Attachment extends TenantEntity {

  @Column(name = "uploader_user_id", nullable = false, updatable = false)
  private UUID uploaderUserId;

  @Column(name = "storage_key", nullable = false, updatable = false, length = 512)
  private String storageKey;

  @Column(name = "file_name", nullable = false, length = 255)
  private String fileName;

  @Column(name = "declared_content_type", nullable = false, updatable = false, length = 128)
  private String declaredContentType;

  @Column(name = "content_type", length = 128)
  private String contentType;

  @Column(name = "declared_size_bytes", nullable = false, updatable = false)
  private long declaredSizeBytes;

  @Column(name = "size_bytes")
  private Long sizeBytes;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private AttachmentStatus status;

  @Column(name = "rejection_reason", length = 255)
  private String rejectionReason;

  @Enumerated(EnumType.STRING)
  @Column(name = "av_result", length = 20)
  private AvResult avResult;

  @Column(name = "av_signature", length = 255)
  private String avSignature;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected Attachment() {}

  public Attachment(
      UUID schoolId,
      UUID uploaderUserId,
      String storageKey,
      String fileName,
      String declaredContentType,
      long declaredSizeBytes) {
    super(schoolId);
    this.uploaderUserId = uploaderUserId;
    this.storageKey = storageKey;
    this.fileName = fileName;
    this.declaredContentType = declaredContentType;
    this.declaredSizeBytes = declaredSizeBytes;
    this.status = AttachmentStatus.PENDING;
  }

  /** Bytes are present; {@code actualSize} came from HeadObject, not from the client. */
  public void markUploaded(long actualSize) {
    this.sizeBytes = actualSize;
    this.status = AttachmentStatus.UPLOADED;
  }

  /** Inspection and scanning both passed. {@code sniffedContentType} came from the stored bytes. */
  public void markClean(String sniffedContentType, AvResult scanResult, Instant when) {
    this.contentType = sniffedContentType;
    this.avResult = scanResult;
    this.status = AttachmentStatus.CLEAN;
    this.completedAt = when;
  }

  /** Failed size, allow-list, or declared-vs-sniffed agreement. */
  public void markRejected(String reason, Instant when) {
    this.rejectionReason = reason;
    this.status = AttachmentStatus.REJECTED;
    this.completedAt = when;
  }

  /** AV matched {@code signature}. */
  public void markInfected(String signature, Instant when) {
    this.avResult = AvResult.INFECTED;
    this.avSignature = signature;
    this.status = AttachmentStatus.INFECTED;
    this.completedAt = when;
  }

  public UUID getUploaderUserId() {
    return uploaderUserId;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public String getFileName() {
    return fileName;
  }

  public String getDeclaredContentType() {
    return declaredContentType;
  }

  public String getContentType() {
    return contentType;
  }

  public long getDeclaredSizeBytes() {
    return declaredSizeBytes;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public AttachmentStatus getStatus() {
    return status;
  }

  public String getRejectionReason() {
    return rejectionReason;
  }

  public AvResult getAvResult() {
    return avResult;
  }

  public String getAvSignature() {
    return avSignature;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}

