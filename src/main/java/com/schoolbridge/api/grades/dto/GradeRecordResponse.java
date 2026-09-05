package com.schoolbridge.api.grades.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record GradeRecordResponse(
    UUID id,
    UUID schoolId,
    UUID studentId,
    UUID classId,
    String subject,
    String period,
    BigDecimal score,
    String gradeLabel,
    UUID gradedByUserId,
    Instant gradedAt,
    String notes,
    Instant createdAt,
    Instant updatedAt) {}
