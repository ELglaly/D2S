package com.schoolbridge.api.homework.dto;

import com.schoolbridge.api.homework.HomeworkDeliveryStatus;
import com.schoolbridge.api.homework.HomeworkStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Flattened projection returned by {@code GET /api/v1/homework?childId=}. Combines the HomeworkItem
 * fields the parent needs with the per-recipient delivery and ack state.
 */
public record ParentHomeworkFeedEntry(
    UUID recipientId,
    UUID homeworkId,
    UUID classId,
    String subject,
    String description,
    String attachmentKey,
    LocalDate dueDate,
    HomeworkStatus homeworkStatus,
    boolean requiresAck,
    HomeworkDeliveryStatus deliveryStatus,
    Instant acknowledgedAt,
    Instant createdAt) {}

