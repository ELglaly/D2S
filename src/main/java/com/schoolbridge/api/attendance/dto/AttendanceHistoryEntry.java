package com.schoolbridge.api.attendance.dto;

import com.schoolbridge.api.attendance.AttendanceStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Row shape for {@code GET /attendance/history}. */
public record AttendanceHistoryEntry(
    UUID recordId,
    UUID studentId,
    UUID classId,
    LocalDate date,
    AttendanceStatus status,
    Instant markedAt,
    boolean parentResponded,
    Instant parentRespondedAt) {}

