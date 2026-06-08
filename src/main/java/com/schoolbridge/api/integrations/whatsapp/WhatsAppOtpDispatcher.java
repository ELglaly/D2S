package com.schoolbridge.api.integrations.whatsapp;

import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.common.i18n.MessageResolver;
import com.schoolbridge.api.identity.otp.OtpDispatcher;
import com.schoolbridge.api.integrations.DispatchRequest;
import com.schoolbridge.api.integrations.DispatchResult;
import com.schoolbridge.api.integrations.NotificationDispatcher;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * M7 OTP delivery: sends the 6-digit code via the parent's WhatsApp number using the {@code
 * parent_otp_v1} template, with SMS fallback handled by {@link NotificationDispatcher}.
 *
 * <p>Replaces {@link com.schoolbridge.api.identity.otp.LoggingOtpDispatcher} in non-test profiles.
 * Language defaults to AR for the MENA-first MVP — a per-user preference field can wire here later.
 *
 * <p>OTP send stays in-process per open-question (1) / recommendation: the OTP latency budget is
 * tight and the existing {@code OtpService.issue(...)} → {@code OtpDispatcher.dispatch(...)} hook
 * is the simplest path. Outbox migration for OTP is deferred to M14.
 */
@Component
@Primary
@Profile("!test")
public class WhatsAppOtpDispatcher implements OtpDispatcher {

  private static final Logger log = LoggerFactory.getLogger(WhatsAppOtpDispatcher.class);

  private final NotificationDispatcher notificationDispatcher;
  private final WhatsAppProperties whatsAppProperties;
  private final MessageResolver messages;

  public WhatsAppOtpDispatcher(
      NotificationDispatcher notificationDispatcher,
      WhatsAppProperties whatsAppProperties,
      MessageResolver messages) {
    this.notificationDispatcher = notificationDispatcher;
    this.whatsAppProperties = whatsAppProperties;
    this.messages = messages;
  }

  @Override
  public void dispatch(String phone, String code) {
    Language language = Language.AR;
    String templateName = whatsAppProperties.getTemplate().getOtpName();
    String smsBody = messages.get("notification.whatsapp.template.parent_otp", code);

    DispatchRequest request =
        new DispatchRequest(
            phone, language, templateName, List.of(TemplateParam.of(code)), smsBody);
    DispatchResult result = notificationDispatcher.dispatch(request);

    if (!result.accepted()) {
      log.warn("otp_dispatch_failed phone={} channel={}", maskPhone(phone), result.channel());
    } else {
      log.info(
          "otp_dispatch_ok phone={} channel={} messageId={}",
          maskPhone(phone),
          result.channel(),
          result.messageId());
    }
  }

  private static String maskPhone(String phone) {
    if (phone == null || phone.length() <= 4) {
      return "***";
    }
    return "***" + phone.substring(phone.length() - 4);
  }
}
