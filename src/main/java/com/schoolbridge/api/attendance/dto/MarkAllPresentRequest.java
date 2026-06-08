package com.schoolbridge.api.attendance.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Bulk-mark request: every enrolled student in {@code classId} that does not already have a row
 * with status PRESENT for {@code date} is upserted to PRESENT. Already-PRESENT rows are skipped.
 */
public record MarkAllPresentRequest(@NotNull UUID classId, @NotNull LocalDate date) {}
