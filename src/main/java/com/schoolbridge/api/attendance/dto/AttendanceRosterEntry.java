package com.schoolbridge.api.attendance.dto;

import com.schoolbridge.api.attendance.AttendanceStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA constructor-projection row for {@code GET /attendance/roster}. {@code recordId} and {@code
 * status} are nullable: a student enrolled in the class but not yet marked for the requested date
 * shows up with both fields null.
 */
public record AttendanceRosterEntry(
    UUID studentId,
    String studentFullName,
    UUID recordId,
    AttendanceStatus status,
    Instant markedAt) {}

