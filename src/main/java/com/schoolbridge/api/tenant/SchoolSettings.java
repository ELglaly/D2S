package com.schoolbridge.api.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Per-school configuration embedded in {@link School}. Single-table layout for MVP because reads
 * always go through the school aggregate; splitting later is a one-shot migration.
 *
 * <p>M8 adds two attendance-related knobs: {@code alertsRespectQuietHours} (opt-in deferral of
 * absence alerts during the configured quiet window) and {@code rosterDueByLocalTime} (the
 * school-local time after which the {@code AttendanceSweeper} flags classes with no roster
 * submitted). Both default to conservative values that preserve the NFR-P2 alert SLA out of the
 * box.
 */
@Embeddable
public class SchoolSettings {

  @Enumerated(EnumType.STRING)
  @Column(name = "default_language", nullable = false, length = 2)
  private Language defaultLanguage;

  @Column(name = "quiet_hours_start", nullable = false)
  private LocalTime quietHoursStart;

  @Column(name = "quiet_hours_end", nullable = false)
  private LocalTime quietHoursEnd;

  @Column(name = "homework_reminder_enabled", nullable = false)
  private boolean homeworkReminderEnabled;

  @Column(name = "homework_reminder_time", nullable = false)
  private LocalTime homeworkReminderTime;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "fee_reminder_offset_days", nullable = false, columnDefinition = "jsonb")
  private List<Integer> feeReminderOffsetDays;

  @Column(name = "waba_phone_number_id", length = 64)
  private String wabaPhoneNumberId;

  @Column(name = "sms_fallback_enabled", nullable = false)
  private boolean smsFallbackEnabled;

  @Column(name = "alerts_respect_quiet_hours", nullable = false)
  private boolean alertsRespectQuietHours;

  @Column(name = "roster_due_by_local_time", nullable = false)
  private LocalTime rosterDueByLocalTime;

  protected SchoolSettings() {}

  public SchoolSettings(
      Language defaultLanguage,
      LocalTime quietHoursStart,
      LocalTime quietHoursEnd,
      boolean homeworkReminderEnabled,
      LocalTime homeworkReminderTime,
      List<Integer> feeReminderOffsetDays,
      String wabaPhoneNumberId,
      boolean smsFallbackEnabled,
      boolean alertsRespectQuietHours,
      LocalTime rosterDueByLocalTime) {
    this.defaultLanguage = defaultLanguage;
    this.quietHoursStart = quietHoursStart;
    this.quietHoursEnd = quietHoursEnd;
    this.homeworkReminderEnabled = homeworkReminderEnabled;
    this.homeworkReminderTime = homeworkReminderTime;
    this.feeReminderOffsetDays = List.copyOf(feeReminderOffsetDays);
    this.wabaPhoneNumberId = wabaPhoneNumberId;
    this.smsFallbackEnabled = smsFallbackEnabled;
    this.alertsRespectQuietHours = alertsRespectQuietHours;
    this.rosterDueByLocalTime = rosterDueByLocalTime;
  }

  /** Default conservative settings used when a school is created without explicit settings. */
  public static SchoolSettings defaults() {
    return new SchoolSettings(
        Language.EN,
        LocalTime.of(21, 0),
        LocalTime.of(7, 0),
        true,
        LocalTime.of(19, 0),
        List.of(-7, -1, 0, 7),
        null,
        false,
        false,
        LocalTime.of(9, 0));
  }

  public Language getDefaultLanguage() {
    return defaultLanguage;
  }

  public LocalTime getQuietHoursStart() {
    return quietHoursStart;
  }

  public LocalTime getQuietHoursEnd() {
    return quietHoursEnd;
  }

  public boolean isHomeworkReminderEnabled() {
    return homeworkReminderEnabled;
  }

  public LocalTime getHomeworkReminderTime() {
    return homeworkReminderTime;
  }

  public List<Integer> getFeeReminderOffsetDays() {
    return feeReminderOffsetDays == null ? List.of() : List.copyOf(feeReminderOffsetDays);
  }

  public String getWabaPhoneNumberId() {
    return wabaPhoneNumberId;
  }

  public boolean isSmsFallbackEnabled() {
    return smsFallbackEnabled;
  }

  public boolean isAlertsRespectQuietHours() {
    return alertsRespectQuietHours;
  }

  public LocalTime getRosterDueByLocalTime() {
    return rosterDueByLocalTime;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SchoolSettings that)) {
      return false;
    }
    return homeworkReminderEnabled == that.homeworkReminderEnabled
        && smsFallbackEnabled == that.smsFallbackEnabled
        && alertsRespectQuietHours == that.alertsRespectQuietHours
        && defaultLanguage == that.defaultLanguage
        && Objects.equals(quietHoursStart, that.quietHoursStart)
        && Objects.equals(quietHoursEnd, that.quietHoursEnd)
        && Objects.equals(homeworkReminderTime, that.homeworkReminderTime)
        && Objects.equals(feeReminderOffsetDays, that.feeReminderOffsetDays)
        && Objects.equals(wabaPhoneNumberId, that.wabaPhoneNumberId)
        && Objects.equals(rosterDueByLocalTime, that.rosterDueByLocalTime);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        defaultLanguage,
        quietHoursStart,
        quietHoursEnd,
        homeworkReminderEnabled,
        homeworkReminderTime,
        feeReminderOffsetDays,
        wabaPhoneNumberId,
        smsFallbackEnabled,
        alertsRespectQuietHours,
        rosterDueByLocalTime);
  }
}
