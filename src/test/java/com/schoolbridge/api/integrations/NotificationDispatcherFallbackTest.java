package com.schoolbridge.api.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.integrations.sms.FakeSmsClient;
import com.schoolbridge.api.integrations.whatsapp.FakeWhatsAppClient;
import com.schoolbridge.api.integrations.whatsapp.TemplateParam;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Verifies the WhatsApp→SMS fallback contract:
 *
 * <ul>
 *   <li>A single WhatsApp failure still attempts WhatsApp next time (below the threshold).
 *   <li>After the configured threshold of consecutive failures, the dispatcher routes to SMS.
 *   <li>A successful WhatsApp send clears the per-recipient failure window.
 * </ul>
 */
@SpringBootTest
class NotificationDispatcherFallbackTest extends AbstractIntegrationTest {

  @Autowired NotificationDispatcher dispatcher;
  @Autowired FakeWhatsAppClient fakeWhatsApp;
  @Autowired FakeSmsClient fakeSms;
  @Autowired StringRedisTemplate redis;

  @BeforeEach
  void resetState() {
    fakeWhatsApp.reset();
    fakeSms.reset();
    java.util.Set<String> keys = redis.keys("dispatcher:wa:fails:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
  }

  @Test
  void firstWhatsAppFailure_fallsBackToSmsForCurrentMessage_butKeepsTryingWhatsAppNext() {
    String phone = "+201000300001";
    DispatchRequest request =
        new DispatchRequest(
            phone, Language.AR, "parent_otp_v1", List.of(TemplateParam.of("111111")), "SMS body");

    // Failure 1 — dispatcher catches the WhatsApp exception and falls back to SMS for THIS message.
    fakeWhatsApp.failNextSend();
    DispatchResult first = dispatcher.dispatch(request);
    assertThat(first.channel()).isEqualTo(NotificationChannel.SMS);
    assertThat(first.accepted()).isTrue();
    assertThat(fakeSms.sent()).hasSize(1);
    assertThat(fakeSms.sent().get(0).recipientPhone()).isEqualTo(phone);

    // Threshold is 2; one failure hasn't tripped it yet, so a fresh dispatch will try WhatsApp.
    DispatchResult second = dispatcher.dispatch(request);
    assertThat(second.channel()).isEqualTo(NotificationChannel.WHATSAPP);
    assertThat(second.accepted()).isTrue();
    assertThat(fakeWhatsApp.sent()).hasSize(1);
  }

  @Test
  void twoConsecutiveWhatsAppFailures_routeNextDispatchStraightToSms() {
    String phone = "+201000300002";
    DispatchRequest request =
        new DispatchRequest(
            phone, Language.AR, "parent_otp_v1", List.of(TemplateParam.of("222222")), "SMS body");

    fakeWhatsApp.failNextSend();
    dispatcher.dispatch(request); // failure 1 — fallback fires once
    fakeWhatsApp.failNextSend();
    dispatcher.dispatch(request); // failure 2 — fallback fires again, threshold now reached

    // Third call: dispatcher should skip WhatsApp entirely.
    fakeWhatsApp.reset();
    fakeSms.reset();
    DispatchResult result = dispatcher.dispatch(request);
    assertThat(result.channel()).isEqualTo(NotificationChannel.SMS);
    assertThat(fakeWhatsApp.sent()).as("threshold reached — WhatsApp must NOT be called").isEmpty();
    assertThat(fakeSms.sent()).hasSize(1);
  }

  @Test
  void successfulWhatsApp_clearsThePerPhoneFailureCounter() {
    String phone = "+201000300003";
    DispatchRequest request =
        new DispatchRequest(
            phone, Language.AR, "parent_otp_v1", List.of(TemplateParam.of("333333")), "SMS body");

    fakeWhatsApp.failNextSend();
    dispatcher.dispatch(request); // failure 1 recorded
    DispatchResult ok = dispatcher.dispatch(request); // succeeds → counter cleared
    assertThat(ok.channel()).isEqualTo(NotificationChannel.WHATSAPP);

    // Now even another single failure stays below threshold and falls back to SMS for this
    // message; the next message tries WhatsApp again (counter restarted at 0+1).
    fakeWhatsApp.reset();
    fakeSms.reset();
    fakeWhatsApp.failNextSend();
    DispatchResult result = dispatcher.dispatch(request);
    assertThat(result.channel()).isEqualTo(NotificationChannel.SMS);

    fakeWhatsApp.reset();
    DispatchResult next = dispatcher.dispatch(request);
    assertThat(next.channel()).isEqualTo(NotificationChannel.WHATSAPP);
  }
}
