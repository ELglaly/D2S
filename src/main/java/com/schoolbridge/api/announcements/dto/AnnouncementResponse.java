package com.schoolbridge.api.announcements.dto;

import com.schoolbridge.api.announcements.enums.AnnouncementScope;
import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import com.schoolbridge.api.announcements.enums.Language;
import java.time.Instant;
import java.util.UUID;

public record AnnouncementResponse(
    UUID id,
    UUID schoolId,
    UUID senderId,
    AnnouncementScope scopeType,
    String scopeValue,
    Language language,
    String body,
    String attachmentKey,
    boolean requiresAck,
    Instant scheduledFor,
    AnnouncementStatus status,
    long recipientCount,
    Instant createdAt,
    Instant updatedAt) {}

