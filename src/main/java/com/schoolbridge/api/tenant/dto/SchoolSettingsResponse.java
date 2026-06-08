package com.schoolbridge.api.tenant.dto;

import com.schoolbridge.api.tenant.Language;
import java.time.LocalTime;
import java.util.List;

public record SchoolSettingsResponse(
    Language defaultLanguage,
    LocalTime quietHoursStart,
    LocalTime quietHoursEnd,
    boolean homeworkReminderEnabled,
    LocalTime homeworkReminderTime,
    List<Integer> feeReminderOffsetDays,
    String wabaPhoneNumberId,
    boolean smsFallbackEnabled,
    boolean alertsRespectQuietHours,
    LocalTime rosterDueByLocalTime) {}
