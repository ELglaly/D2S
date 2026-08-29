package com.schoolbridge.api.homework.dto;

import com.schoolbridge.api.homework.HomeworkItem;
import com.schoolbridge.api.homework.HomeworkRecipient;
import org.springframework.stereotype.Component;

@Component
public class HomeworkMapper {

  public HomeworkResponse toResponse(HomeworkItem h, long recipientCount, long acknowledgedCount) {
    return new HomeworkResponse(
        h.getId(),
        h.getSchoolId(),
        h.getClassId(),
        h.getTeacherId(),
        h.getSubject(),
        h.getDescription(),
        h.getAttachmentKey(),
        h.getDueDate(),
        h.getStatus(),
        h.isRequiresAck(),
        recipientCount,
        acknowledgedCount,
        h.getReminderSentAt(),
        h.getCreatedAt(),
        h.getUpdatedAt());
  }

  public HomeworkRecipientResponse toRecipientResponse(HomeworkRecipient r) {
    return new HomeworkRecipientResponse(
        r.getId(),
        r.getHomeworkId(),
        r.getParentUserId(),
        r.getStudentId(),
        r.getDeliveryStatus(),
        r.getMessageId(),
        r.getAcknowledgedAt(),
        r.getCreatedAt());
  }

  public ParentHomeworkFeedEntry toFeedEntry(HomeworkRecipient r, HomeworkItem h) {
    return new ParentHomeworkFeedEntry(
        r.getId(),
        h.getId(),
        h.getClassId(),
        h.getSubject(),
        h.getDescription(),
        h.getAttachmentKey(),
        h.getDueDate(),
        h.getStatus(),
        h.isRequiresAck(),
        r.getDeliveryStatus(),
        r.getAcknowledgedAt(),
        r.getCreatedAt());
  }
}

