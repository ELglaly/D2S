package com.schoolbridge.api.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.device.DevicePlatform;
import com.schoolbridge.api.identity.device.DeviceToken;
import com.schoolbridge.api.identity.device.DeviceTokenRepository;
import com.schoolbridge.api.integrations.push.FakePushClient;
import com.schoolbridge.api.integrations.sms.FakeSmsClient;
import com.schoolbridge.api.integrations.whatsapp.FakeWhatsAppClient;
import com.schoolbridge.api.integrations.whatsapp.TemplateParam;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The channel-ordered walk: first channel that accepts wins, and a channel with no way to reach the
 * user is skipped rather than counted as a failure.
 *
 * <p>The distinction matters because "no device registered" and "push rejected the token" must not
 * produce the same outcome — the first should fall through silently, the second is a real delivery
 * problem worth a metric.
 */
@SpringBootTest
class NotificationDispatcherChannelTest extends AbstractIntegrationTest {

  @Autowired NotificationDispatcher dispatcher;
  @Autowired FakePushClient fakePush;
  @Autowired FakeWhatsAppClient fakeWhatsApp;
  @Autowired FakeSmsClient fakeSms;
  @Autowired DeviceTokenRepository deviceTokens;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired StringRedisTemplate redis;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID userId;

  @BeforeEach
  void setUp() {
    TenantContext.clear();
    fakePush.reset();
    fakeWhatsApp.reset();
    fakeSms.reset();
    Set<String> keys = redis.keys("dispatcher:wa:fails:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
    tx.executeWithoutResult(s -> deviceTokens.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Channel School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());
    userId =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.staff(
                            schoolId,
                            UserRole.SCHOOL_ADMIN,
                            "Parent",
                            "parent@channel.test",
                            passwordEncoder.encode("pass")))
                    .getId());
    TenantContext.set(schoolId);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void pushFirst_whenTheUserHasARegisteredDevice() {
    registerDevice("fcm-token-1");

    DispatchResult result =
        tx.execute(s -> dispatcher.dispatch(request("+201000400001"), defaultOrder()));

    assertThat(result.channel()).isEqualTo(NotificationChannel.PUSH);
    assertThat(result.accepted()).isTrue();
    assertThat(fakePush.getSends()).hasSize(1);
    assertThat(fakePush.getSends().get(0).fcmToken()).isEqualTo("fcm-token-1");
    assertThat(fakeWhatsApp.sent()).as("a satisfied push must not also burn a template").isEmpty();
  }

  @Test
  void everyRegisteredDeviceGetsIt_andOneAcceptanceIsEnough() {
    registerDevice("fcm-phone");
    registerDevice("fcm-tablet");

    DispatchResult result =
        tx.execute(s -> dispatcher.dispatch(request("+201000400002"), defaultOrder()));

    assertThat(result.accepted()).isTrue();
    assertThat(fakePush.getSends()).as("a parent's phone and tablet should both buzz").hasSize(2);
  }

  @Test
  void noDeviceRegistered_fallsThroughToWhatsAppWithoutRecordingAFailure() {
    DispatchResult result =
        tx.execute(s -> dispatcher.dispatch(request("+201000400003"), defaultOrder()));

    assertThat(result.channel()).isEqualTo(NotificationChannel.WHATSAPP);
    assertThat(result.accepted()).isTrue();
    assertThat(fakePush.getSends()).isEmpty();
    assertThat(fakeWhatsApp.sent()).hasSize(1);
  }

  @Test
  void whatsAppFailure_stillFallsToSmsUnderneathTheNewOrdering() {
    fakeWhatsApp.failNextSend();

    DispatchResult result =
        tx.execute(s -> dispatcher.dispatch(request("+201000400004"), defaultOrder()));

    assertThat(result.channel()).isEqualTo(NotificationChannel.SMS);
    assertThat(result.accepted()).isTrue();
    assertThat(fakeSms.sent()).hasSize(1);
  }

  @Test
  void aUserWithNoPhoneAndNoDevice_producesANotAcceptedResultRatherThanAnException() {
    DispatchResult result = tx.execute(s -> dispatcher.dispatch(request(null), defaultOrder()));

    assertThat(result.accepted()).isFalse();
    assertThat(fakeWhatsApp.sent()).isEmpty();
    assertThat(fakeSms.sent()).isEmpty();
  }

  @Test
  void channelOrderIsHonoured_smsBeforeWhatsAppWhenTheUserSaidSo() {
    registerDevice("fcm-ignored");

    DispatchResult result =
        tx.execute(
            s ->
                dispatcher.dispatch(
                    request("+201000400005"),
                    List.of(NotificationChannel.SMS, NotificationChannel.WHATSAPP)));

    assertThat(result.channel()).isEqualTo(NotificationChannel.SMS);
    assertThat(fakePush.getSends())
        .as("push was not in the list, so it must not be tried")
        .isEmpty();
  }

  private static List<NotificationChannel> defaultOrder() {
    return NotificationChannel.DEFAULT_ORDER;
  }

  private UserDispatchRequest request(String phone) {
    DispatchRequest content =
        new DispatchRequest(
            phone,
            Language.EN,
            "homework_reminder_v1",
            List.of(TemplateParam.of("Maths"), TemplateParam.of("2026-03-11")),
            "Homework: Maths. Due 2026-03-11.");
    Map<String, String> data = new HashMap<>();
    data.put("type", "homework");
    return new UserDispatchRequest(
        new NotificationTarget(schoolId, userId, phone),
        content,
        "Homework reminder",
        "Homework: Maths. Due 2026-03-11.",
        data);
  }

  private void registerDevice(String token) {
    tx.executeWithoutResult(
        s ->
            deviceTokens.save(
                new DeviceToken(
                    schoolId, userId, DevicePlatform.ANDROID, token, "device-" + token)));
  }
}
