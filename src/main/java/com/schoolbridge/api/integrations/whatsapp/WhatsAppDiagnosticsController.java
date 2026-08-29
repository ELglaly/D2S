package com.schoolbridge.api.integrations.whatsapp;

import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.integrations.DispatchRequest;
import com.schoolbridge.api.integrations.DispatchResult;
import com.schoolbridge.api.integrations.NotificationDispatcher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Admin-only endpoint for diagnosing the WhatsApp integration status. Shows which client is active,
 * which env vars are missing, and exposes an optional live credential ping against Meta's API.
 */
@RestController
@RequestMapping("/integrations/whatsapp/diagnostics")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(
    name = "WhatsApp Diagnostics",
    description = "WhatsApp integration status and credential check (SUPER_ADMIN only)")
public class WhatsAppDiagnosticsController {

  private static final Logger log = LoggerFactory.getLogger(WhatsAppDiagnosticsController.class);

  private final WhatsAppClient whatsAppClient;
  private final WhatsAppProperties properties;
  private final NotificationDispatcher dispatcher;

  public WhatsAppDiagnosticsController(
      WhatsAppClient whatsAppClient,
      WhatsAppProperties properties,
      NotificationDispatcher dispatcher) {
    this.whatsAppClient = whatsAppClient;
    this.properties = properties;
    this.dispatcher = dispatcher;
  }

  @GetMapping
  @Operation(
      summary = "WhatsApp integration status",
      description = "Shows credential configuration and which client is active")
  public StatusResponse status() {
    boolean isLive = whatsAppClient instanceof MetaCloudWhatsAppClient;

    List<String> missing = new ArrayList<>();
    if (isBlank(properties.getAccessToken())) missing.add("WHATSAPP_ACCESS_TOKEN");
    if (isBlank(properties.getPhoneNumberId())) missing.add("WHATSAPP_PHONE_NUMBER_ID");
    if (isBlank(properties.getAppSecret())) missing.add("WHATSAPP_APP_SECRET");
    if (isBlank(properties.getVerifyToken())) missing.add("WHATSAPP_VERIFY_TOKEN");

    return new StatusResponse(
        isLive,
        isLive
            ? "MetaCloudWhatsAppClient (LIVE â€” real sends)"
            : "LoggingWhatsAppClient (STUB â€” logs only, no real sends)",
        missing,
        mask(properties.getPhoneNumberId()),
        new TemplateNames(
            properties.getTemplate().getOtpName(),
            properties.getTemplate().getAnnouncementName(),
            properties.getTemplate().getAttendanceAbsentName(),
            properties.getTemplate().getAttendanceLateName(),
            properties.getTemplate().getAttendanceExcusedName()));
  }

  /**
   * Calls Meta API to verify the access token and phone number ID are valid. Only useful when
   * credentials are configured ({@code activeClient = MetaCloudWhatsAppClient}).
   */
  @GetMapping("/ping")
  @Operation(
      summary = "Ping Meta API",
      description =
          "Calls Meta Graph API to verify the access token and phone number ID. Returns the phone's display name on success.")
  public PingResponse ping() {
    if (isBlank(properties.getAccessToken()) || isBlank(properties.getPhoneNumberId())) {
      return new PingResponse(
          false,
          "Credentials not configured â€” set WHATSAPP_ACCESS_TOKEN and WHATSAPP_PHONE_NUMBER_ID first",
          null);
    }
    try {
      SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
      factory.setConnectTimeout(5_000);
      factory.setReadTimeout(10_000);
      RestClient client =
          RestClient.builder()
              .baseUrl(properties.getApiBaseUrl())
              .requestFactory(factory)
              .defaultHeader("Authorization", "Bearer " + properties.getAccessToken())
              .build();

      PhoneNumberInfo info =
          client
              .get()
              .uri(
                  "/{id}?fields=display_phone_number,verified_name,quality_rating",
                  properties.getPhoneNumberId())
              .retrieve()
              .body(PhoneNumberInfo.class);

      if (info == null) {
        return new PingResponse(false, "Empty response from Meta API", null);
      }
      log.info(
          "whatsapp_diagnostics_ping_ok phone={} name={}",
          info.display_phone_number(),
          info.verified_name());
      return new PingResponse(
          true,
          "Connected â€” phone: "
              + info.display_phone_number()
              + " | name: "
              + info.verified_name()
              + " | quality: "
              + info.quality_rating(),
          info.display_phone_number());
    } catch (Exception ex) {
      log.warn("whatsapp_diagnostics_ping_failed cause={}", ex.getMessage());
      return new PingResponse(false, "Meta API error: " + ex.getMessage(), null);
    }
  }

  /**
   * Sends a real test WhatsApp message to the given phone number using the OTP template. Requires
   * live credentials and a pre-approved template.
   */
  @GetMapping("/test-send")
  @Operation(
      summary = "Send a test WhatsApp message",
      description =
          "Sends the OTP template to the given E.164 phone (e.g. +201234567890). Only works with live credentials and approved templates.")
  public TestSendResponse testSend(@RequestParam String phone) {
    if (isBlank(properties.getAccessToken())) {
      return new TestSendResponse(false, "STUB", null, "Credentials not configured");
    }
    try {
      DispatchResult result =
          dispatcher.dispatch(
              new DispatchRequest(
                  phone,
                  Language.AR,
                  properties.getTemplate().getOtpName(),
                  List.of(TemplateParam.of("123456")),
                  "Test message from SchoolBridge diagnostics"));
      return new TestSendResponse(
          result.accepted(),
          result.channel().name(),
          result.messageId(),
          result.accepted() ? "Message dispatched" : "Dispatch failed â€” check logs");
    } catch (Exception ex) {
      log.warn("whatsapp_diagnostics_test_send_failed phone={} cause={}", phone, ex.getMessage());
      return new TestSendResponse(false, "ERROR", null, ex.getMessage());
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static String mask(String value) {
    if (value == null || value.length() <= 4) return "***";
    return value.substring(0, 3) + "***" + value.substring(value.length() - 3);
  }

  record StatusResponse(
      boolean credentialsConfigured,
      String activeClient,
      List<String> missingEnvVars,
      String phoneNumberIdMasked,
      TemplateNames templates) {}

  record TemplateNames(
      String otp,
      String announcement,
      String attendanceAbsent,
      String attendanceLate,
      String attendanceExcused) {}

  record PingResponse(boolean ok, String message, String displayPhone) {}

  record TestSendResponse(boolean accepted, String channel, String messageId, String message) {}

  record PhoneNumberInfo(
      String display_phone_number, String verified_name, String quality_rating) {}
}

