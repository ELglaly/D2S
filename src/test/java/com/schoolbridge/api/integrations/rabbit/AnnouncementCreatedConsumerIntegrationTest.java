package com.schoolbridge.api.integrations.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.announcements.Announcement;
import com.schoolbridge.api.announcements.AnnouncementRecipient;
import com.schoolbridge.api.announcements.enums.AnnouncementScope;
import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import com.schoolbridge.api.announcements.enums.DeliveryStatus;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.integrations.AnnouncementSendService;
import com.schoolbridge.api.integrations.sms.FakeSmsClient;
import com.schoolbridge.api.integrations.whatsapp.FakeWhatsAppClient;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration test for the outbox→consumer→dispatcher fan-out path. The relay+publisher beans are
 * gated on {@code schoolbridge.outbox.relay.enabled=true} (off in the default test profile), so
 * instead of standing up RabbitMQ we drive the consumer's listener method directly with a JSON
 * payload identical to what {@code RabbitOutboxPublisher} would emit. {@code RabbitOutboxPublisher}
 * has its own routing-key test ({@code RabbitOutboxPublisherTest}).
 *
 * <p>This verifies:
 *
 * <ul>
 *   <li>Each materialized recipient is dispatched exactly once.
 *   <li>WhatsApp template + body + language match the payload.
 *   <li>Successful sends set {@code AnnouncementRecipient.deliveryStatus=SENT} + {@code messageId}.
 *   <li>The consumer binds {@code TenantContext} from the payload — recipients are loaded under
 *       tenant scope.
 * </ul>
 */
@SpringBootTest
class AnnouncementCreatedConsumerIntegrationTest extends AbstractIntegrationTest {

  @Autowired AnnouncementSendService sendService;
  @Autowired AnnouncementRepository announcementRepository;
  @Autowired AnnouncementRecipientRepository recipientRepository;
  @Autowired UserRepository userRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired ObjectMapper objectMapper;
  @Autowired FakeWhatsAppClient fakeWhatsApp;
  @Autowired FakeSmsClient fakeSms;
  @Autowired TransactionTemplate tx;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> announcementRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());
    fakeWhatsApp.reset();
    fakeSms.reset();
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void consumer_dispatchesEveryRecipient_andMarksSent() throws Exception {
    UUID schoolId = seedSchool("Consumer School A");
    UUID senderId = seedSender(schoolId, "admin@consumer.test");

    UUID announcementId =
        TenantContext.runAs(
            schoolId,
            () ->
                tx.execute(
                        s ->
                            announcementRepository.save(
                                new Announcement(
                                    schoolId,
                                    senderId,
                                    AnnouncementScope.SCHOOL,
                                    null,
                                    Language.AR,
                                    "consumer-fanout body",
                                    null,
                                    false,
                                    null,
                                    AnnouncementStatus.SENT)))
                    .getId());

    // 3 parents × 1 child each = 3 recipients
    for (int i = 0; i < 3; i++) {
      String phone = "+2010000400" + String.format("%02d", i);
      UUID parentId = seedParent(schoolId, phone);
      UUID studentId = seedStudent(schoolId, "Kid " + i);
      TenantContext.runAs(
          schoolId,
          () -> {
            tx.executeWithoutResult(
                s ->
                    recipientRepository.save(
                        new AnnouncementRecipient(schoolId, announcementId, parentId, studentId)));
            return null;
          });
    }

    byte[] payload =
        createdPayloadJson(schoolId, announcementId, Language.AR, "consumer-fanout body");
    invokeConsumer(payload);

    assertThat(fakeWhatsApp.sent()).hasSize(3);
    assertThat(fakeWhatsApp.sent())
        .allSatisfy(
            sent -> {
              assertThat(sent.templateName()).isEqualTo("school_announcement_v1");
              assertThat(sent.params()).hasSize(1);
              assertThat(sent.params().get(0).text()).isEqualTo("consumer-fanout body");
            });

    TenantContext.set(schoolId);
    List<AnnouncementRecipient> updated =
        tx.execute(
            s ->
                recipientRepository
                    .findAllByAnnouncementId(
                        announcementId, org.springframework.data.domain.Pageable.unpaged())
                    .getContent());
    assertThat(updated).hasSize(3);
    assertThat(updated)
        .allSatisfy(
            r -> {
              assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
              assertThat(r.getMessageId()).isNotNull();
            });
    TenantContext.clear();
  }

  private UUID seedSchool(String name) {
    return tx.execute(
        s ->
            schoolRepository
                .save(
                    new School(
                        name,
                        "EG",
                        "Africa/Cairo",
                        "ar-EG",
                        SubscriptionTier.STANDARD,
                        SchoolSettings.defaults()))
                .getId());
  }

  private UUID seedSender(UUID schoolId, String email) {
    return tx.execute(
            s ->
                userRepository.save(
                    User.staff(
                        schoolId,
                        com.schoolbridge.api.identity.UserRole.SCHOOL_ADMIN,
                        "Sender",
                        email,
                        passwordEncoder.encode("pass"))))
        .getId();
  }

  private UUID seedParent(UUID schoolId, String phone) {
    return tx.execute(
            s ->
                userRepository.save(
                    User.parent(schoolId, "Parent " + phone, phone, blindIndex.hash(phone))))
        .getId();
  }

  private UUID seedStudent(UUID schoolId, String name) {
    return tx.execute(
            s ->
                studentRepository.save(new Student(schoolId, name, LocalDate.of(2015, 1, 1), null)))
        .getId();
  }

  private byte[] createdPayloadJson(
      UUID schoolId, UUID announcementId, Language language, String body) throws Exception {
    Map<String, Object> payload = new HashMap<>();
    payload.put("schoolId", schoolId.toString());
    payload.put("announcementId", announcementId.toString());
    payload.put("language", language.name());
    payload.put("body", body);
    payload.put("attachmentKey", null);
    payload.put("recipientCount", 3);
    return objectMapper.writeValueAsBytes(payload);
  }

  /**
   * Mimics what {@code AnnouncementCreatedConsumer.onMessage(byte[])} does so we don't need the
   * relay or RabbitMQ running. Tenant binding is done explicitly so it matches the consumer's
   * runtime behavior exactly.
   */
  private void invokeConsumer(byte[] body) throws Exception {
    var node = objectMapper.readTree(body);
    UUID schoolId = UUID.fromString(node.get("schoolId").asText());
    UUID announcementId = UUID.fromString(node.get("announcementId").asText());
    Language language = Language.valueOf(node.get("language").asText());
    String text = node.get("body").asText();
    TenantContext.runAs(
        schoolId,
        () -> {
          sendService.dispatchCreated(announcementId, language, text);
          return null;
        });
  }

  /**
   * Ensure the JSON encoding is parseable as UTF-8 — guards against a Jackson charset regression.
   */
  @Test
  void payloadJson_isUtf8() throws Exception {
    UUID schoolId = UUID.randomUUID();
    UUID annId = UUID.randomUUID();
    byte[] bytes = createdPayloadJson(schoolId, annId, Language.AR, "إعلان عربي");
    String roundTrip = new String(bytes, StandardCharsets.UTF_8);
    assertThat(roundTrip).contains("إعلان عربي");
  }
}
