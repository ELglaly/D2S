package com.schoolbridge.api.announcements.dto;

import com.schoolbridge.api.announcements.Announcement;
import com.schoolbridge.api.announcements.AnnouncementRecipient;
import org.springframework.stereotype.Component;

/** Explicit hand-written mapper for the M6 aggregates. */
@Component
public class AnnouncementsMapper {

  public AnnouncementResponse toResponse(Announcement a, long recipientCount) {
    return new AnnouncementResponse(
        a.getId(),
        a.getSchoolId(),
        a.getSenderId(),
        a.getScopeType(),
        a.getScopeValue(),
        a.getLanguage(),
        a.getBody(),
        a.getAttachmentKey(),
        a.isRequiresAck(),
        a.getScheduledFor(),
        a.getStatus(),
        recipientCount,
        a.getCreatedAt(),
        a.getUpdatedAt());
  }

  public AnnouncementRecipientResponse toResponse(AnnouncementRecipient r) {
    return new AnnouncementRecipientResponse(
        r.getId(),
        r.getAnnouncementId(),
        r.getParentUserId(),
        r.getStudentId(),
        r.getDeliveryStatus(),
        r.getAcknowledgedAt(),
        r.getMessageId(),
        r.getCreatedAt());
  }
}

