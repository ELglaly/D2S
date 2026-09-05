package com.schoolbridge.api.integrations.sms;

import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.integrations.whatsapp.MessageSendResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder SMS adapter for environments without a real provider. Logs the would-be send at WARN
 * and returns an {@code accepted} result with a synthetic message id. Registered by {@link
 * com.schoolbridge.api.integrations.IntegrationsStubConfig} when no real {@code SmsClient} bean is
 * present.
 */
public class LoggingSmsClient implements SmsClient {

  private static final Logger log = LoggerFactory.getLogger(LoggingSmsClient.class);

  @Override
  public MessageSendResult send(String recipientPhone, String body, Language language) {
    log.warn(
        "sms_dispatch_no_provider phone={} bodyLen={} lang={} â€” install a real SmsClient in M14",
        maskPhone(recipientPhone),
        body == null ? 0 : body.length(),
        language);
    return MessageSendResult.accepted("sms-logging-" + UUID.randomUUID());
  }

  private static String maskPhone(String phone) {
    if (phone == null || phone.length() <= 4) {
      return "***";
    }
    return "***" + phone.substring(phone.length() - 4);
  }
}
