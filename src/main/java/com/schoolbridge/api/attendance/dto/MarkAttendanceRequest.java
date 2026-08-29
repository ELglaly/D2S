package com.schoolbridge.api.attendance.dto;

import com.schoolbridge.api.attendance.AttendanceStatus;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Single-student mark request. {@code (studentId, classId, date)} is the upsert key â€” the same
 * triple submitted twice with the same status is a no-op.
 */
public record MarkAttendanceRequest(
    @NotNull UUID studentId,
    @NotNull UUID classId,
    @NotNull LocalDate date,
    @NotNull AttendanceStatus status) {}

