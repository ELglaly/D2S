package com.schoolbridge.api.integrations.whatsapp;

import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.common.error.IntegrationException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-process WhatsApp client used in tests. Records every send so assertions can verify what was
 * dispatched without standing up WireMock; can be programmed to throw to drive fallback paths.
 *
 * <p>Adapter-level network tests use WireMock against {@link MetaCloudWhatsAppClient} directly (see
 * {@code MetaCloudWhatsAppClientWireMockTest}); this fake exists for dispatcher/consumer tests
 * where the wire format is not the subject under test. {@code @Profile("test")} wins because {@link
 * MetaCloudWhatsAppClient} is gated to {@code @Profile("!test")}.
 */
@Component
@Profile("test")
@Primary
public class FakeWhatsAppClient implements WhatsAppClient {

  /** A captured send. {@code accepted=false} means the fake was programmed to fail this call. */
  public record SentMessage(
      String templateName, String recipientPhone, Language language, List<TemplateParam> params) {}

  private final ConcurrentLinkedQueue<SentMessage> sent = new ConcurrentLinkedQueue<>();
  private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();

  @Override
  public MessageSendResult sendTemplate(
      String templateName, String recipientPhone, Language language, List<TemplateParam> params) {
    RuntimeException failure = nextFailure.getAndSet(null);
    if (failure != null) {
      throw failure;
    }
    sent.add(new SentMessage(templateName, recipientPhone, language, params));
    return MessageSendResult.accepted("fake-msg-" + UUID.randomUUID());
  }

  @Override
  public MessageSendResult sendText(String recipientPhone, String body) {
    RuntimeException failure = nextFailure.getAndSet(null);
    if (failure != null) {
      throw failure;
    }
    sent.add(
        new SentMessage("__text__", recipientPhone, Language.EN, List.of(TemplateParam.of(body))));
    return MessageSendResult.accepted("fake-msg-" + UUID.randomUUID());
  }

  /** The send after this call will throw the supplied exception, then resume normal behavior. */
  public void failNextSendWith(RuntimeException ex) {
    nextFailure.set(ex);
  }

  /**
   * Convenience: fail the next send with the {@link IntegrationException} the dispatcher expects.
   */
  public void failNextSend() {
    failNextSendWith(new IntegrationException("error.whatsapp.unavailable"));
  }

  public List<SentMessage> sent() {
    return List.copyOf(sent);
  }

  public void reset() {
    sent.clear();
    nextFailure.set(null);
  }
}
