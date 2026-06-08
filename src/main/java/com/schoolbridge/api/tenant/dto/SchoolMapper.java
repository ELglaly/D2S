package com.schoolbridge.api.tenant.dto;

import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolSettings;
import org.springframework.stereotype.Component;

/**
 * Hand-written mapper. ModelMapper is configured app-wide for future modules, but record-based DTOs
 * with nested records map predictably with explicit code and keep the transformation obvious at
 * review time.
 */
@Component
public class SchoolMapper {

  public SchoolResponse toResponse(School school) {
    return new SchoolResponse(
        school.getId(),
        school.getName(),
        school.getCountry(),
        school.getTimezone(),
        school.getLocale(),
        school.getSubscriptionTier(),
        school.getStatus(),
        toSettingsResponse(school.getSettings()),
        school.getCreatedAt(),
        school.getUpdatedAt());
  }

  public SchoolSettingsResponse toSettingsResponse(SchoolSettings settings) {
    return new SchoolSettingsResponse(
        settings.getDefaultLanguage(),
        settings.getQuietHoursStart(),
        settings.getQuietHoursEnd(),
        settings.isHomeworkReminderEnabled(),
        settings.getHomeworkReminderTime(),
        settings.getFeeReminderOffsetDays(),
        settings.getWabaPhoneNumberId(),
        settings.isSmsFallbackEnabled(),
        settings.isAlertsRespectQuietHours(),
        settings.getRosterDueByLocalTime());
  }

  public SchoolSettings toSettings(SchoolSettingsRequest request) {
    return new SchoolSettings(
        request.defaultLanguage(),
        request.quietHoursStart(),
        request.quietHoursEnd(),
        request.homeworkReminderEnabled(),
        request.homeworkReminderTime(),
        request.feeReminderOffsetDays(),
        request.wabaPhoneNumberId(),
        request.smsFallbackEnabled(),
        request.alertsRespectQuietHours(),
        request.rosterDueByLocalTime());
  }
}
