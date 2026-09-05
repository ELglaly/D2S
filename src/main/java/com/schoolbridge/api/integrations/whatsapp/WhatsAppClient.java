package com.schoolbridge.api.integrations.whatsapp;

import com.schoolbridge.api.announcements.enums.Language;
import java.util.List;

/**
 * Hex-boundary port for sending outbound WhatsApp messages. The cloud implementation is {@link
 * MetaCloudWhatsAppClient}; tests get a {@code FakeWhatsAppClient}.
 *
 * <p>Templates: the only outbound path Meta permits without an open 24h "customer session" is a
 * pre-approved template. Free-form {@link #sendText} is reserved for later modules (M11 parentâ†”
 * teacher messaging) where the recipient initiated the session.
 */
public interface WhatsAppClient {

  /**
   * Sends a pre-approved template message.
   *
   * @param templateName the Meta template name (e.g. {@code parent_otp_v1})
   * @param recipientPhone E.164-formatted destination
   * @param language template language (matches Meta's {@code language.code}; AR â†’ {@code ar}, EN
   *     â†’ {@code en})
   * @param params positional body parameters substituted into the template's {@code {{1}}}, {@code
   *     {{2}}}, â€¦ slots, in order
   * @return the result with the provider message id on success
   * @throws com.schoolbridge.api.common.error.IntegrationException on any unrecoverable failure
   *     (4xx or terminal 5xx after retries)
   */
  MessageSendResult sendTemplate(
      String templateName, String recipientPhone, Language language, List<TemplateParam> params);

  /**
   * Free-form text send, valid only when an inbound customer session is currently open. Not used in
   * M7 â€” kept on the port so M11 doesn't need to widen the interface.
   */
  MessageSendResult sendText(String recipientPhone, String body);
}
