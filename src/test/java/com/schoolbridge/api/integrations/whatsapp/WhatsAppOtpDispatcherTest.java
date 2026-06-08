package com.schoolbridge.api.integrations.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.common.i18n.MessageResolver;
import com.schoolbridge.api.integrations.NotificationDispatcher;
import com.schoolbridge.api.integrations.sms.FakeSmsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Verifies the {@link WhatsAppOtpDispatcher} contract end-to-end against the test profile's {@link
 * FakeWhatsAppClient}. Because the production bean carries {@code @Profile("!test")}, the
 * dispatcher is constructed manually with the real injected collaborators (FakeWhatsAppClient is
 * wired through {@link NotificationDispatcher} as if a real cloud client were behind it).
 *
 * <p>The integration target is the wider parent OTP flow ({@code ParentAuthIntegrationTest} keeps
 * working via {@code CapturingOtpDispatcher}); this test focuses on the swap itself:
 *
 * <ul>
 *   <li>The OTP code is passed as the first positional WhatsApp template parameter.
 *   <li>The template name is {@code parent_otp_v1} (the M7 default).
 *   <li>If WhatsApp succeeds, no SMS is sent.
 *   <li>If WhatsApp fails, SMS fallback receives the rendered body containing the code.
 * </ul>
 */
@SpringBootTest
class WhatsAppOtpDispatcherTest extends AbstractIntegrationTest {

  @Autowired NotificationDispatcher notificationDispatcher;
  @Autowired WhatsAppProperties whatsAppProperties;
  @Autowired MessageResolver messages;
  @Autowired FakeWhatsAppClient fakeWhatsApp;
  @Autowired FakeSmsClient fakeSms;
  @Autowired StringRedisTemplate redis;

  private WhatsAppOtpDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    fakeWhatsApp.reset();
    fakeSms.reset();
    java.util.Set<String> keys = redis.keys("dispatcher:wa:fails:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
    dispatcher = new WhatsAppOtpDispatcher(notificationDispatcher, whatsAppProperties, messages);
  }

  @Test
  void dispatch_onSuccess_sendsTemplateWithCodeAsFirstParameter() {
    dispatcher.dispatch("+201500001111", "246810");

    assertThat(fakeWhatsApp.sent()).hasSize(1);
    var sent = fakeWhatsApp.sent().get(0);
    assertThat(sent.templateName()).isEqualTo("parent_otp_v1");
    assertThat(sent.recipientPhone()).isEqualTo("+201500001111");
    assertThat(sent.params()).hasSize(1);
    assertThat(sent.params().get(0).text()).isEqualTo("246810");
    assertThat(fakeSms.sent()).isEmpty();
  }

  @Test
  void dispatch_onWhatsAppFailure_fallsBackToSmsWithRenderedBody() {
    fakeWhatsApp.failNextSend();

    dispatcher.dispatch("+201500002222", "135790");

    assertThat(fakeWhatsApp.sent()).isEmpty();
    assertThat(fakeSms.sent()).hasSize(1);
    var sms = fakeSms.sent().get(0);
    assertThat(sms.recipientPhone()).isEqualTo("+201500002222");
    assertThat(sms.body())
        .as("SMS fallback body must contain the OTP code rendered from i18n")
        .contains("135790");
  }
}
