package com.schoolbridge.api.homework.dto;

import com.schoolbridge.api.homework.HomeworkDeliveryStatus;
import java.time.Instant;
import java.util.UUID;

public record HomeworkRecipientResponse(
    UUID id,
    UUID homeworkId,
    UUID parentUserId,
    UUID studentId,
    HomeworkDeliveryStatus deliveryStatus,
    String messageId,
    Instant acknowledgedAt,
    Instant createdAt) {}

