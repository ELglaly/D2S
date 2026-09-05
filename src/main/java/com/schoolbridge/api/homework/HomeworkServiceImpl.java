package com.schoolbridge.api.homework;

import com.schoolbridge.api.attachments.AttachmentService;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.ConflictException;
import com.schoolbridge.api.common.error.NotFoundException;
import com.schoolbridge.api.common.outbox.OutboxEventRecorder;
import com.schoolbridge.api.homework.dto.CreateHomeworkRequest;
import com.schoolbridge.api.homework.dto.HomeworkMapper;
import com.schoolbridge.api.homework.dto.HomeworkRecipientResponse;
import com.schoolbridge.api.homework.dto.HomeworkResponse;
import com.schoolbridge.api.homework.dto.ParentHomeworkFeedEntry;
import com.schoolbridge.api.homework.dto.UpdateHomeworkRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HomeworkServiceImpl implements HomeworkService {

  private static final String AGGREGATE_TYPE = "HomeworkItem";
  private static final String EVENT_PUBLISHED = "homework.published";
  private static final String EVENT_ARCHIVED = "homework.archived";

  private final HomeworkItemRepository homeworkItems;
  private final HomeworkRecipientRepository recipients;
  private final ParentStudentLinkRepository parentLinks;
  private final HomeworkMapper mapper;
  private final OutboxEventRecorder outbox;
  private final AuditService auditService;
  private final AttachmentService attachments;

  public HomeworkServiceImpl(
      HomeworkItemRepository homeworkItems,
      HomeworkRecipientRepository recipients,
      ParentStudentLinkRepository parentLinks,
      HomeworkMapper mapper,
      OutboxEventRecorder outbox,
      AuditService auditService,
      AttachmentService attachments) {
    this.homeworkItems = homeworkItems;
    this.recipients = recipients;
    this.parentLinks = parentLinks;
    this.mapper = mapper;
    this.outbox = outbox;
    this.auditService = auditService;
    this.attachments = attachments;
  }

  @Override
  @Transactional
  public HomeworkResponse create(UUID schoolId, UUID actorId, CreateHomeworkRequest request) {
    HomeworkStatus initialStatus =
        request.publish() ? HomeworkStatus.PUBLISHED : HomeworkStatus.DRAFT;

    // attachmentKey is an attachment id, not a free string: it must exist in this school and have
    // passed inspection. Publishing an item that points at an object still being scanned would put
    // a link in front of parents before anyone knows the file is safe.
    attachments.requireUsableReference(schoolId, request.attachmentKey());

    HomeworkItem item =
        new HomeworkItem(
            schoolId,
            request.classId(),
            actorId,
            request.subject(),
            request.description(),
            request.attachmentKey(),
            request.dueDate(),
            request.requiresAck(),
            initialStatus);
    HomeworkItem saved = homeworkItems.save(item);

    long recipientCount = 0;
    if (initialStatus == HomeworkStatus.PUBLISHED) {
      recipientCount = materializeRecipients(saved);
      recordPublishedEvent(saved, schoolId, actorId, recipientCount);
    }

    return mapper.toResponse(saved, recipientCount, 0L);
  }

  @Override
  @Transactional
  public HomeworkResponse publish(UUID id) {
    HomeworkItem item = requireHomework(id);
    if (item.getStatus() == HomeworkStatus.PUBLISHED) {
      throw new ConflictException("error.homework.already_published", id);
    }
    if (item.getStatus() == HomeworkStatus.ARCHIVED) {
      throw new ConflictException("error.homework.archived_cannot_modify", id);
    }
    item.publish();

    long recipientCount = materializeRecipients(item);
    recordPublishedEvent(item, item.getSchoolId(), item.getTeacherId(), recipientCount);

    return mapper.toResponse(item, recipientCount, 0L);
  }

  @Override
  @Transactional(readOnly = true)
  public HomeworkResponse findById(UUID id) {
    HomeworkItem item = requireHomework(id);
    return mapper.toResponse(
        item,
        recipients.countByHomeworkId(id),
        recipients.countByHomeworkIdAndAcknowledgedAtIsNotNull(id));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<HomeworkResponse> list(
      UUID classId,
      HomeworkStatus status,
      LocalDate dueDateFrom,
      LocalDate dueDateTo,
      Pageable pageable) {
    return homeworkItems
        .findFiltered(classId, status, dueDateFrom, dueDateTo, pageable)
        .map(
            h ->
                mapper.toResponse(
                    h,
                    recipients.countByHomeworkId(h.getId()),
                    recipients.countByHomeworkIdAndAcknowledgedAtIsNotNull(h.getId())));
  }

  @Override
  @Transactional
  public HomeworkResponse update(UUID id, UpdateHomeworkRequest request) {
    HomeworkItem item = requireHomework(id);
    if (item.getStatus() == HomeworkStatus.ARCHIVED) {
      throw new ConflictException("error.homework.archived_cannot_modify", id);
    }
    attachments.requireUsableReference(item.getSchoolId(), request.attachmentKey());
    item.update(
        request.subject(), request.description(), request.attachmentKey(), request.dueDate());
    return mapper.toResponse(
        item,
        recipients.countByHomeworkId(id),
        recipients.countByHomeworkIdAndAcknowledgedAtIsNotNull(id));
  }

  @Override
  @Transactional
  public void archive(UUID id) {
    HomeworkItem item = requireHomework(id);
    item.archive();

    Map<String, Object> payload = new HashMap<>();
    payload.put("homeworkId", item.getId());
    payload.put("schoolId", item.getSchoolId());
    payload.put("classId", item.getClassId());
    payload.put("traceId", MDC.get("traceId"));
    outbox.record(item.getSchoolId(), AGGREGATE_TYPE, item.getId(), EVENT_ARCHIVED, payload);

    auditService.record(
        item.getSchoolId(),
        null,
        EVENT_ARCHIVED,
        AGGREGATE_TYPE,
        item.getId(),
        Map.of("classId", item.getClassId().toString()));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ParentHomeworkFeedEntry> parentFeed(
      UUID parentUserId, UUID childId, Pageable pageable) {
    // Anti-enumeration: 404 if the parent is not linked to this child at all.
    if (!parentLinks.existsByParentUserIdAndStudentId(parentUserId, childId)) {
      throw new NotFoundException("error.homework.not_found", childId);
    }
    Page<HomeworkRecipient> page =
        recipients.findFeedForParentAndStudent(parentUserId, childId, pageable);
    return page.map(
        r -> {
          HomeworkItem h = requireHomework(r.getHomeworkId());
          return mapper.toFeedEntry(r, h);
        });
  }

  @Override
  @Transactional
  public void acknowledge(UUID homeworkId, UUID parentUserId) {
    HomeworkItem item = requireHomework(homeworkId);
    HomeworkRecipient recipient =
        recipients
            .findFirstByHomeworkIdAndParentUserId(homeworkId, parentUserId)
            .orElseThrow(
                () -> new NotFoundException("error.homework.not_in_recipients", homeworkId));

    // Silent no-op when requiresAck=false, per OQ6 decision.
    recipient.acknowledge(Instant.now());

    auditService.record(
        item.getSchoolId(),
        parentUserId,
        "homework.acknowledged",
        AGGREGATE_TYPE,
        item.getId(),
        Map.of("recipientId", recipient.getId().toString()));
  }

  @Override
  @Transactional(readOnly = true)
  public HomeworkResponse findByIdForParent(UUID id, UUID parentUserId) {
    HomeworkItem item = requireHomework(id);
    if (!recipients.findFirstByHomeworkIdAndParentUserId(id, parentUserId).isPresent()) {
      throw new NotFoundException("error.homework.not_found", id);
    }
    return mapper.toResponse(
        item,
        recipients.countByHomeworkId(id),
        recipients.countByHomeworkIdAndAcknowledgedAtIsNotNull(id));
  }

  @Override
  @Transactional(readOnly = true)
  public List<HomeworkRecipientResponse> listRecipients(UUID homeworkId) {
    requireHomework(homeworkId);
    return recipients.findAllByHomeworkId(homeworkId).stream()
        .map(mapper::toRecipientResponse)
        .toList();
  }

  HomeworkItem requireHomework(UUID id) {
    return homeworkItems
        .findById(id)
        .orElseThrow(() -> new NotFoundException("error.homework.not_found", id));
  }

  private long materializeRecipients(HomeworkItem item) {
    List<ParentStudentLink> links = parentLinks.findAllForClassAnnouncementScope(item.getClassId());
    if (links.isEmpty()) {
      return 0;
    }
    Set<String> seen = new HashSet<>();
    Map<String, HomeworkRecipient> deduped = new LinkedHashMap<>();
    for (ParentStudentLink link : links) {
      String key = link.getParentUserId() + ":" + link.getStudentId();
      if (seen.add(key)) {
        deduped.put(
            key,
            new HomeworkRecipient(
                item.getSchoolId(), item.getId(), link.getParentUserId(), link.getStudentId()));
      }
    }
    recipients.saveAll(deduped.values());
    return deduped.size();
  }

  private void recordPublishedEvent(
      HomeworkItem item, UUID schoolId, UUID actorId, long recipientCount) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("homeworkId", item.getId());
    payload.put("schoolId", schoolId);
    payload.put("classId", item.getClassId());
    payload.put("dueDate", item.getDueDate().toString());
    payload.put("recipientCount", recipientCount);
    payload.put("attachmentKey", item.getAttachmentKey());
    payload.put("traceId", MDC.get("traceId"));
    outbox.record(schoolId, AGGREGATE_TYPE, item.getId(), EVENT_PUBLISHED, payload);

    auditService.record(
        schoolId,
        actorId,
        EVENT_PUBLISHED,
        AGGREGATE_TYPE,
        item.getId(),
        Map.of(
            "classId", item.getClassId().toString(),
            "recipientCount", String.valueOf(recipientCount)));
  }
}
