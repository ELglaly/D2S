package com.schoolbridge.api.announcements.dto;

import com.schoolbridge.api.announcements.enums.DeliveryStatus;
import java.time.Instant;
import java.util.UUID;

public record AnnouncementRecipientResponse(
    UUID id,
    UUID announcementId,
    UUID parentUserId,
    UUID studentId,
    DeliveryStatus deliveryStatus,
    Instant acknowledgedAt,
    String messageId,
    Instant createdAt) {}
