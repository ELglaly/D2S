package com.schoolbridge.api.tenant.dto;

import com.schoolbridge.api.tenant.Language;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;

/** Used for both create-time and update-time settings payloads. */
public record SchoolSettingsRequest(
    @NotNull Language defaultLanguage,
    @NotNull LocalTime quietHoursStart,
    @NotNull LocalTime quietHoursEnd,
    boolean homeworkReminderEnabled,
    @NotNull LocalTime homeworkReminderTime,
    @NotEmpty @Size(max = 10) List<@Min(-30) @Max(30) Integer> feeReminderOffsetDays,
    @Size(max = 64) String wabaPhoneNumberId,
    boolean smsFallbackEnabled,
    boolean alertsRespectQuietHours,
    @NotNull LocalTime rosterDueByLocalTime) {}

