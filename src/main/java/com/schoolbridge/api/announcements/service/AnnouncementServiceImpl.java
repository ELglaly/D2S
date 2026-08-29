package com.schoolbridge.api.announcements.service;

import com.schoolbridge.api.announcements.*;
import com.schoolbridge.api.announcements.dto.AnnouncementRecipientResponse;
import com.schoolbridge.api.announcements.dto.AnnouncementResponse;
import com.schoolbridge.api.announcements.dto.AnnouncementsMapper;
import com.schoolbridge.api.announcements.dto.CreateAnnouncementRequest;
import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.attachments.AttachmentService;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.ConflictException;
import com.schoolbridge.api.common.error.NotFoundException;
import com.schoolbridge.api.common.error.ValidationException;
import com.schoolbridge.api.common.outbox.OutboxEventRecorder;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnnouncementServiceImpl implements AnnouncementService {

  private static final String AGGREGATE_TYPE = "Announcement";
  private static final String EVENT_CREATED = "announcement.created";
  private static final String EVENT_RECALLED = "announcement.recalled";

  private final AnnouncementRepository announcements;
  private final AnnouncementRecipientRepository recipients;
  private final ParentStudentLinkRepository parentLinks;
  private final AnnouncementsMapper mapper;
  private final OutboxEventRecorder outbox;
  private final AuditService auditService;
  private final AttachmentService attachments;

  public AnnouncementServiceImpl(
      AnnouncementRepository announcements,
      AnnouncementRecipientRepository recipients,
      ParentStudentLinkRepository parentLinks,
      AnnouncementsMapper mapper,
      OutboxEventRecorder outbox,
      AuditService auditService,
      AttachmentService attachments) {
    this.announcements = announcements;
    this.recipients = recipients;
    this.parentLinks = parentLinks;
    this.mapper = mapper;
    this.outbox = outbox;
    this.auditService = auditService;
    this.attachments = attachments;
  }

  @Override
  @Transactional
  public AnnouncementResponse create(
      UUID schoolId, UUID senderId, CreateAnnouncementRequest request) {
    String scopeValue = validateAndExtractScopeValue(request);
    // attachmentKey is an attachment id, not a free string: it must exist in this school and have
    // passed inspection before it can go out to parents.
    attachments.requireUsableReference(schoolId, request.attachmentKey());
    AnnouncementStatus initialStatus =
        request.scheduledFor() != null ? AnnouncementStatus.SCHEDULED : AnnouncementStatus.SENT;

    Announcement entity =
        new Announcement(
            schoolId,
            senderId,
            request.scopeType(),
            scopeValue,
            request.language(),
            request.body(),
            request.attachmentKey(),
            request.requiresAck(),
            request.scheduledFor(),
            initialStatus);
    Announcement saved = announcements.save(entity);

    List<ParentStudentLink> links = resolveLinksForScope(request);
    long recipientCount = materializeRecipients(saved, links);

    // Only dispatch now if this is not scheduled. Recording the outbox event unconditionally meant
    // an announcement scheduled for next week was delivered within seconds while the UI still
    // showed SCHEDULED â€” the schedule was decorative. AnnouncementScheduleSweeper records the event
    // when scheduledFor actually arrives.
    if (initialStatus == AnnouncementStatus.SENT) {
      outbox.record(
          schoolId,
          AGGREGATE_TYPE,
          saved.getId(),
          EVENT_CREATED,
          dispatchPayload(saved, recipientCount));
    }

    auditService.record(
        schoolId,
        senderId,
        "announcement.created",
        AGGREGATE_TYPE,
        saved.getId(),
        Map.of("scopeType", saved.getScopeType().name(), "recipientCount", recipientCount));

    return mapper.toResponse(saved, recipientCount);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AnnouncementResponse> list(AnnouncementStatus status, Pageable pageable) {
    Page<Announcement> page =
        status == null
            ? announcements.findAll(pageable)
            : announcements.findAllByStatus(status, pageable);
    // One grouped count for the whole page instead of one query per row.
    Map<UUID, Long> counts = recipientCounts(page.map(Announcement::getId).toList());
    return page.map(a -> mapper.toResponse(a, counts.getOrDefault(a.getId(), 0L)));
  }

  /**
   * Dispatch payload for {@code announcement.created}. Built with {@link HashMap}, never {@code
   * Map.of} â€” {@code attachmentKey} is nullable and {@code Map.of} throws NPE on a null value.
   */
  private Map<String, Object> dispatchPayload(Announcement saved, long recipientCount) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("announcementId", saved.getId());
    payload.put("schoolId", saved.getSchoolId());
    payload.put("language", saved.getLanguage());
    payload.put("body", saved.getBody());
    payload.put("attachmentKey", saved.getAttachmentKey());
    payload.put("recipientCount", recipientCount);
    return payload;
  }

  /** Recipient counts keyed by announcement id; ids with no recipients are simply absent. */
  private Map<UUID, Long> recipientCounts(List<UUID> announcementIds) {
    if (announcementIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Long> counts = new HashMap<>();
    for (Object[] row : recipients.countByAnnouncementIdIn(announcementIds)) {
      counts.put((UUID) row[0], (Long) row[1]);
    }
    return counts;
  }

  @Override
  @Transactional(readOnly = true)
  public AnnouncementResponse findById(UUID id) {
    Announcement entity = requireAnnouncement(id);
    return mapper.toResponse(entity, recipients.countByAnnouncementId(id));
  }

  @Override
  @Transactional
  public AnnouncementResponse recall(UUID id) {
    Announcement entity = requireAnnouncement(id);
    if (entity.getStatus() == AnnouncementStatus.RECALLED) {
      throw new ConflictException("error.announcement.already_recalled", id);
    }
    entity.recall();

    Map<String, Object> recalledPayload = new HashMap<>();
    recalledPayload.put("announcementId", entity.getId());
    recalledPayload.put("schoolId", entity.getSchoolId());
    outbox.record(
        entity.getSchoolId(), AGGREGATE_TYPE, entity.getId(), EVENT_RECALLED, recalledPayload);

    auditService.record(
        entity.getSchoolId(),
        null,
        "announcement.recalled",
        AGGREGATE_TYPE,
        entity.getId(),
        Map.of());

    return mapper.toResponse(entity, recipients.countByAnnouncementId(id));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AnnouncementRecipientResponse> listRecipients(UUID id, Pageable pageable) {
    requireAnnouncement(id);
    return recipients.findAllByAnnouncementId(id, pageable).map(mapper::toResponse);
  }

  @Override
  @Transactional
  public void acknowledge(UUID announcementId, UUID parentUserId) {
    Announcement entity = requireAnnouncement(announcementId);
    // One row per linked child. A parent sees a single announcement and acknowledges once, so the
    // tap must clear every row they hold for it â€” otherwise a parent with two children stays
    // permanently "unacknowledged" for the sibling they never had a way to acknowledge separately.
    List<AnnouncementRecipient> rows =
        recipients.findAllByAnnouncementIdAndParentUserId(announcementId, parentUserId);
    if (rows.isEmpty()) {
      throw new NotFoundException("error.announcement.not_in_recipients", announcementId);
    }
    Instant now = Instant.now();
    rows.forEach(r -> r.acknowledge(now));

    auditService.record(
        entity.getSchoolId(),
        parentUserId,
        "announcement.acknowledged",
        AGGREGATE_TYPE,
        entity.getId(),
        Map.of("recipientIds", rows.stream().map(AnnouncementRecipient::getId).toList()));
  }

  Announcement requireAnnouncement(UUID id) {
    return announcements
        .findById(id)
        .orElseThrow(() -> new NotFoundException("error.announcement.not_found", id));
  }

  private String validateAndExtractScopeValue(CreateAnnouncementRequest request) {
    return switch (request.scopeType()) {
      case SCHOOL -> {
        if (request.classId() != null
            || (request.gradeLevel() != null && !request.gradeLevel().isBlank())
            || (request.recipientStudentIds() != null
                && !request.recipientStudentIds().isEmpty())) {
          throw new ValidationException("error.announcement.invalid_scope");
        }
        yield null;
      }
      case GRADE -> {
        if (request.gradeLevel() == null || request.gradeLevel().isBlank()) {
          throw new ValidationException("error.announcement.invalid_scope");
        }
        if (request.classId() != null
            || (request.recipientStudentIds() != null
                && !request.recipientStudentIds().isEmpty())) {
          throw new ValidationException("error.announcement.invalid_scope");
        }
        yield request.gradeLevel().trim();
      }
      case CLASS -> {
        if (request.classId() == null) {
          throw new ValidationException("error.announcement.invalid_scope");
        }
        if ((request.gradeLevel() != null && !request.gradeLevel().isBlank())
            || (request.recipientStudentIds() != null
                && !request.recipientStudentIds().isEmpty())) {
          throw new ValidationException("error.announcement.invalid_scope");
        }
        yield request.classId().toString();
      }
      case CUSTOM -> {
        if (request.recipientStudentIds() == null || request.recipientStudentIds().isEmpty()) {
          throw new ValidationException("error.announcement.invalid_scope");
        }
        if (request.classId() != null
            || (request.gradeLevel() != null && !request.gradeLevel().isBlank())) {
          throw new ValidationException("error.announcement.invalid_scope");
        }
        yield null;
      }
    };
  }

  private List<ParentStudentLink> resolveLinksForScope(CreateAnnouncementRequest request) {
    return switch (request.scopeType()) {
      case SCHOOL -> parentLinks.findAllForSchoolAnnouncementScope();
      case GRADE -> parentLinks.findAllForGradeAnnouncementScope(request.gradeLevel().trim());
      case CLASS -> parentLinks.findAllForClassAnnouncementScope(request.classId());
      case CUSTOM -> parentLinks.findAllByStudentIdIn(request.recipientStudentIds());
    };
  }

  /**
   * Inserts one recipient row per (parent, student) pair, deduplicated so the unique index does not
   * fire on accidental duplicates (e.g. a parent linked to the same student through two link rows
   * after a data migration). Insertion order is preserved for deterministic test assertions.
   */
  private long materializeRecipients(Announcement saved, List<ParentStudentLink> links) {
    if (links.isEmpty()) {
      return 0;
    }
    Set<String> seen = new HashSet<>();
    Map<String, AnnouncementRecipient> deduped = new LinkedHashMap<>();
    for (ParentStudentLink link : links) {
      String key = link.getParentUserId() + ":" + link.getStudentId();
      if (seen.add(key)) {
        deduped.put(
            key,
            new AnnouncementRecipient(
                saved.getSchoolId(), saved.getId(), link.getParentUserId(), link.getStudentId()));
      }
    }
    recipients.saveAll(deduped.values());
    return deduped.size();
  }
}

