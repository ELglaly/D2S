package com.schoolbridge.api.attachments;

import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.attachments.av.AvScanner;
import com.schoolbridge.api.attachments.dto.AttachmentDownloadTicket;
import com.schoolbridge.api.attachments.dto.AttachmentMapper;
import com.schoolbridge.api.attachments.dto.AttachmentResponse;
import com.schoolbridge.api.attachments.dto.AttachmentUploadTicket;
import com.schoolbridge.api.attachments.dto.CreateAttachmentRequest;
import com.schoolbridge.api.attachments.storage.ObjectStorage;
import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.ConflictException;
import com.schoolbridge.api.common.error.NotFoundException;
import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.common.error.ValidationException;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.homework.HomeworkItemRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** See {@link AttachmentService}. */
@Service
public class AttachmentServiceImpl implements AttachmentService {

  private static final Logger log = LoggerFactory.getLogger(AttachmentServiceImpl.class);

  private final AttachmentRepository repository;
  private final ObjectStorage storage;
  private final ContentTypeSniffer sniffer;
  private final AvScanner avScanner;
  private final AuditService auditService;
  private final HomeworkItemRepository homeworkItems;
  private final AnnouncementRepository announcements;
  private final StorageProperties properties;

  public AttachmentServiceImpl(
      AttachmentRepository repository,
      ObjectStorage storage,
      ContentTypeSniffer sniffer,
      AvScanner avScanner,
      AuditService auditService,
      HomeworkItemRepository homeworkItems,
      AnnouncementRepository announcements,
      StorageProperties properties) {
    this.repository = repository;
    this.storage = storage;
    this.sniffer = sniffer;
    this.avScanner = avScanner;
    this.auditService = auditService;
    this.homeworkItems = homeworkItems;
    this.announcements = announcements;
    this.properties = properties;
  }

  @Override
  @Transactional
  public AttachmentUploadTicket createUpload(
      UUID schoolId, UUID uploaderUserId, CreateAttachmentRequest request) {
    if (request.sizeBytes() > properties.getMaxUploadBytes()) {
      throw new ValidationException(
          "error.attachment.too_large", request.sizeBytes(), properties.getMaxUploadBytes());
    }
    String declaredType = normalizeContentType(request.contentType());
    if (!properties.getAllowedContentTypes().contains(declaredType)) {
      // Rejecting the declaration early is convenience, not security — the binding check happens
      // against the sniffed type at completion. Doing it here saves the client an upload it was
      // always going to lose.
      throw new ValidationException("error.attachment.type_not_allowed", declaredType);
    }

    Instant now = Instant.now();
    // The object id is generated here rather than reusing the entity id, which Hibernate does not
    // assign until persist. The key is immutable (updatable=false), so it has to be right on the
    // first save — a placeholder followed by an update is not an option, and a flush-then-update
    // dance would be one more thing to get wrong. What matters is that the value is server-side and
    // unguessable, not that it equals the row id; storage_key is uniquely indexed for lookups.
    String key = AttachmentKeys.forAttachment(schoolId, UUID.randomUUID(), now);
    Attachment attachment =
        repository.save(
            new Attachment(
                schoolId,
                uploaderUserId,
                key,
                request.fileName(),
                declaredType,
                request.sizeBytes()));

    Duration ttl = properties.getUploadUrlTtl();
    ObjectStorage.PresignedUpload presigned =
        storage.presignPut(key, declaredType, request.sizeBytes(), ttl);

    return new AttachmentUploadTicket(
        attachment.getId(), presigned.url(), "PUT", presigned.requiredHeaders(), now.plus(ttl));
  }

  @Override
  @Transactional
  public AttachmentResponse complete(UUID attachmentId) {
    Attachment attachment = requireInTenant(attachmentId);
    if (attachment.getStatus().isTerminal()) {
      throw new ConflictException("error.attachment.already_completed", attachmentId);
    }

    ObjectStorage.StoredObject stored =
        storage
            .head(attachment.getStorageKey())
            .orElseThrow(
                () -> new ConflictException("error.attachment.not_uploaded", attachmentId));

    Instant now = Instant.now();
    attachment.markUploaded(stored.sizeBytes());

    // Re-checked against the real object rather than the declaration: a backend with laxer
    // signature semantics than S3 could otherwise let a larger body through the signed length.
    if (stored.sizeBytes() > properties.getMaxUploadBytes()) {
      return rejectAndDelete(
          attachment, "size " + stored.sizeBytes() + " exceeds the maximum", now);
    }

    byte[] head = storage.readHead(attachment.getStorageKey(), properties.getSniffBytes());
    String sniffed = sniffer.sniff(head).orElse(null);
    if (sniffed == null || !properties.getAllowedContentTypes().contains(sniffed)) {
      return rejectAndDelete(attachment, "content type is not on the allow-list", now);
    }
    if (!sniffed.equals(attachment.getDeclaredContentType())) {
      // The whole point of sniffing. A file named .pdf, declared application/pdf, carrying PNG or
      // PE magic is the upload this check exists for.
      return rejectAndDelete(
          attachment,
          "declared " + attachment.getDeclaredContentType() + " but content is " + sniffed,
          now);
    }

    AvScanner.ScanOutcome outcome = scan(attachment.getStorageKey());
    if (outcome.isInfected()) {
      storage.delete(attachment.getStorageKey());
      attachment.markInfected(outcome.signature(), now);
      repository.save(attachment);
      log.warn(
          "attachment_infected id={} school={} signature={}",
          attachment.getId(),
          attachment.getSchoolId(),
          outcome.signature());
      // A 422 rather than a 200-with-status: the client asked to finish an upload and it did not
      // finish. Nothing downloadable was produced.
      throw new ValidationException("error.attachment.infected");
    }

    attachment.markClean(sniffed, outcome.result(), now);
    repository.save(attachment);

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("fileName", attachment.getFileName());
    metadata.put("contentType", sniffed);
    metadata.put("sizeBytes", stored.sizeBytes());
    metadata.put("avResult", outcome.result().name());
    auditService.record(
        attachment.getSchoolId(),
        attachment.getUploaderUserId(),
        "ATTACHMENT_UPLOADED",
        "Attachment",
        attachment.getId(),
        metadata);

    return AttachmentMapper.toResponse(attachment);
  }

  @Override
  @Transactional(readOnly = true)
  public AttachmentResponse get(UUID attachmentId) {
    return AttachmentMapper.toResponse(requireInTenant(attachmentId));
  }

  @Override
  @Transactional(readOnly = true)
  public AttachmentDownloadTicket download(UUID attachmentId) {
    Attachment attachment = requireInTenant(attachmentId);
    if (!attachment.getStatus().isDownloadable()) {
      throw new ConflictException("error.attachment.not_downloadable", attachmentId);
    }
    Duration ttl = properties.getDownloadUrlTtl();
    String url =
        storage.presignGet(
            attachment.getStorageKey(), attachment.getFileName(), attachment.getContentType(), ttl);
    return new AttachmentDownloadTicket(
        attachment.getId(),
        url,
        attachment.getFileName(),
        attachment.getContentType(),
        Instant.now().plus(ttl));
  }

  @Override
  @Transactional
  public void delete(UUID attachmentId, UUID actorUserId) {
    Attachment attachment = requireInTenant(attachmentId);
    String reference = attachment.getId().toString();
    long referencing =
        homeworkItems.countReferencingAttachment(reference)
            + announcements.countReferencingAttachment(reference);
    if (referencing > 0) {
      // Deleting under a live reference leaves homework or an announcement pointing at nothing,
      // and the parent-facing feed has no way to render that.
      throw new ConflictException("error.attachment.in_use", attachmentId, referencing);
    }

    storage.delete(attachment.getStorageKey());
    repository.delete(attachment);

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("fileName", attachment.getFileName());
    metadata.put("status", attachment.getStatus().name());
    auditService.record(
        attachment.getSchoolId(),
        actorUserId,
        "ATTACHMENT_DELETED",
        "Attachment",
        attachment.getId(),
        metadata);
  }

  @Override
  @Transactional(readOnly = true)
  public void requireUsableReference(UUID schoolId, String attachmentReference) {
    if (attachmentReference == null || attachmentReference.isBlank()) {
      return;
    }
    UUID attachmentId;
    try {
      attachmentId = UUID.fromString(attachmentReference);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("error.attachment.invalid_reference", attachmentReference);
    }
    Attachment attachment =
        repository
            .findById(attachmentId)
            .orElseThrow(
                () ->
                    new ValidationException(
                        "error.attachment.invalid_reference", attachmentReference));
    if (!attachment.getSchoolId().equals(schoolId)) {
      throw new TenantSecurityException();
    }
    if (!attachment.getStatus().isDownloadable()) {
      // Attaching something still being inspected would let an item go out to parents pointing at
      // an object that may yet turn out to be infected.
      throw new ValidationException("error.attachment.not_usable", attachmentReference);
    }
  }

  private AvScanner.ScanOutcome scan(String key) {
    try (InputStream stream = storage.openStream(key)) {
      return avScanner.scan(stream);
    } catch (IOException e) {
      throw new com.schoolbridge.api.common.error.IntegrationException(
          "error.attachment.av_unavailable", e);
    }
  }

  private AttachmentResponse rejectAndDelete(Attachment attachment, String reason, Instant now) {
    storage.delete(attachment.getStorageKey());
    attachment.markRejected(reason, now);
    repository.save(attachment);
    log.info(
        "attachment_rejected id={} school={} reason={}",
        attachment.getId(),
        attachment.getSchoolId(),
        reason);
    return AttachmentMapper.toResponse(attachment);
  }

  /**
   * Loads within the caller's tenant. The Hibernate filter and the changelog-018 RLS policy both
   * scope this already; the explicit comparison is the third layer, and the one that produces a
   * deliberate 403-with-audit rather than a bare 404 when something has gone wrong upstream.
   */
  private Attachment requireInTenant(UUID attachmentId) {
    Attachment attachment =
        repository
            .findById(attachmentId)
            .orElseThrow(() -> new NotFoundException("error.attachment.not_found", attachmentId));
    TenantContext.get()
        .filter(tenant -> !attachment.getSchoolId().equals(tenant))
        .ifPresent(
            tenant -> {
              throw new TenantSecurityException();
            });
    return attachment;
  }

  private static String normalizeContentType(String contentType) {
    // "image/jpeg; charset=binary" and casing variants are both common from mobile clients.
    int semicolon = contentType.indexOf(';');
    String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
    return base.trim().toLowerCase(java.util.Locale.ROOT);
  }
}
