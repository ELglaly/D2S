package com.schoolbridge.api.attendance.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Summary returned by {@code POST /attendance:mark-all-present}. {@code transitionedCount} is the
 * number of students that were not already PRESENT and were upserted; {@code skippedCount} is the
 * number that were already PRESENT and left untouched.
 */
public record MarkAllPresentResponse(
    UUID classId, LocalDate date, int transitionedCount, int skippedCount) {}

