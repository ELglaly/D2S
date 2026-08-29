package com.schoolbridge.api.integrations.whatsapp;

import com.schoolbridge.api.announcements.enums.Language;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub WhatsApp adapter for environments without Meta Cloud API credentials. Logs the would-be send
 * at WARN and returns an {@code accepted} result with a synthetic message id. Does NOT actually
 * deliver any message. Registered by {@link
 * com.schoolbridge.api.integrations.IntegrationsStubConfig} when no real {@code WhatsAppClient}
 * bean is present.
 */
public class LoggingWhatsAppClient implements WhatsAppClient {

  private static final Logger log = LoggerFactory.getLogger(LoggingWhatsAppClient.class);

  @Override
  public MessageSendResult sendTemplate(
      String templateName, String recipientPhone, Language language, List<TemplateParam> params) {
    log.warn(
        "whatsapp_stub_send template={} phone={} lang={} â€” set schoolbridge.whatsapp.access-token for real delivery",
        templateName,
        maskPhone(recipientPhone),
        language);
    return MessageSendResult.accepted("wa-logging-" + UUID.randomUUID());
  }

  @Override
  public MessageSendResult sendText(String recipientPhone, String body) {
    log.warn(
        "whatsapp_stub_send_text phone={} â€” set schoolbridge.whatsapp.access-token for real delivery",
        maskPhone(recipientPhone));
    return MessageSendResult.accepted("wa-logging-" + UUID.randomUUID());
  }

  private static String maskPhone(String phone) {
    if (phone == null || phone.length() <= 4) {
      return "***";
    }
    return "***" + phone.substring(phone.length() - 4);
  }
}

