package com.schoolbridge.api.integrations.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed binding for {@code schoolbridge.whatsapp.*}. */
@ConfigurationProperties(prefix = "schoolbridge.whatsapp")
public class WhatsAppProperties {

  /** Base URL (no trailing slash). Defaults to Meta Cloud API v20.0. */
  private String apiBaseUrl = "https://graph.facebook.com/v20.0";

  private String phoneNumberId;

  private String accessToken;

  /** HMAC-SHA256 key used to verify {@code X-Hub-Signature-256} on the webhook. */
  private String appSecret;

  /** Verify token used in the {@code GET /webhook} subscribe handshake. */
  private String verifyToken;

  private final Template template = new Template();

  public String getApiBaseUrl() {
    return apiBaseUrl;
  }

  public void setApiBaseUrl(String apiBaseUrl) {
    this.apiBaseUrl = apiBaseUrl;
  }

  public String getPhoneNumberId() {
    return phoneNumberId;
  }

  public void setPhoneNumberId(String phoneNumberId) {
    this.phoneNumberId = phoneNumberId;
  }

  public String getAccessToken() {
    return accessToken;
  }

  public void setAccessToken(String accessToken) {
    this.accessToken = accessToken;
  }

  public String getAppSecret() {
    return appSecret;
  }

  public void setAppSecret(String appSecret) {
    this.appSecret = appSecret;
  }

  public String getVerifyToken() {
    return verifyToken;
  }

  public void setVerifyToken(String verifyToken) {
    this.verifyToken = verifyToken;
  }

  public Template getTemplate() {
    return template;
  }

  public static class Template {

    private String otpName = "parent_otp_v1";
    private String announcementName = "school_announcement_v1";
    private String attendanceAbsentName = "attendance_absent_v1";
    private String attendanceLateName = "attendance_late_v1";
    private String attendanceExcusedName = "attendance_excused_v1";
    private String homeworkReminderName = "homework_reminder_v1";

    public String getOtpName() {
      return otpName;
    }

    public void setOtpName(String otpName) {
      this.otpName = otpName;
    }

    public String getAnnouncementName() {
      return announcementName;
    }

    public void setAnnouncementName(String announcementName) {
      this.announcementName = announcementName;
    }

    public String getAttendanceAbsentName() {
      return attendanceAbsentName;
    }

    public void setAttendanceAbsentName(String attendanceAbsentName) {
      this.attendanceAbsentName = attendanceAbsentName;
    }

    public String getAttendanceLateName() {
      return attendanceLateName;
    }

    public void setAttendanceLateName(String attendanceLateName) {
      this.attendanceLateName = attendanceLateName;
    }

    public String getAttendanceExcusedName() {
      return attendanceExcusedName;
    }

    public void setAttendanceExcusedName(String attendanceExcusedName) {
      this.attendanceExcusedName = attendanceExcusedName;
    }

    public String getHomeworkReminderName() {
      return homeworkReminderName;
    }

    public void setHomeworkReminderName(String homeworkReminderName) {
      this.homeworkReminderName = homeworkReminderName;
    }
  }
}

