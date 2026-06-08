package com.schoolbridge.api.integrations.sms;

import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.integrations.whatsapp.MessageSendResult;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test-scope SMS client. Captures every send for assertion. A real provider lands in M14 per the M7
 * handoff. {@code @Profile("test")} replaces the production {@code LoggingSmsClient} during tests.
 */
@Component
@Profile("test")
@Primary
public class FakeSmsClient implements SmsClient {

  public record SentSms(String recipientPhone, String body, Language language) {}

  private final ConcurrentLinkedQueue<SentSms> sent = new ConcurrentLinkedQueue<>();

  @Override
  public MessageSendResult send(String recipientPhone, String body, Language language) {
    sent.add(new SentSms(recipientPhone, body, language));
    return MessageSendResult.accepted("fake-sms-" + UUID.randomUUID());
  }

  public List<SentSms> sent() {
    return List.copyOf(sent);
  }

  public void reset() {
    sent.clear();
  }
}
