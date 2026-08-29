package com.schoolbridge.api.attendance.dto;

import com.schoolbridge.api.attendance.AttendanceStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Returned by {@code POST /attendance:mark} and {@code POST /attendance/{id}/parent-response}. */
public record AttendanceRecordResponse(
    UUID id,
    UUID schoolId,
    UUID studentId,
    UUID classId,
    LocalDate date,
    AttendanceStatus status,
    UUID markedByUserId,
    Instant markedAt,
    boolean parentResponded,
    Instant parentRespondedAt,
    Instant alertSentAt) {}

