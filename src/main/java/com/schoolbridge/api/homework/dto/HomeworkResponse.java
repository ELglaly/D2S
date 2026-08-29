package com.schoolbridge.api.homework.dto;

import com.schoolbridge.api.homework.HomeworkStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record HomeworkResponse(
    UUID id,
    UUID schoolId,
    UUID classId,
    UUID teacherId,
    String subject,
    String description,
    String attachmentKey,
    LocalDate dueDate,
    HomeworkStatus status,
    boolean requiresAck,
    long recipientCount,
    long acknowledgedCount,
    Instant reminderSentAt,
    Instant createdAt,
    Instant updatedAt) {}

