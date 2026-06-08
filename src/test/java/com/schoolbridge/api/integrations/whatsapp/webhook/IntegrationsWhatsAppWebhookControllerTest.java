package com.schoolbridge.api.integrations.whatsapp.webhook;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

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
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.integrations.whatsapp.WhatsAppProperties;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end test of the WhatsApp webhook security + idempotency contract.
 *
 * <ul>
 *   <li>GET subscribe with the correct verify token → 200 + challenge body
 *   <li>GET subscribe with a wrong verify token → 403
 *   <li>POST with a valid HMAC → 200 and the matching recipient's deliveryStatus advances
 *   <li>POST with a missing/invalid HMAC → 401
 *   <li>POST repeating the same status row → no further mutation (idempotent)
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntegrationsWhatsAppWebhookControllerTest extends AbstractIntegrationTest {

  @LocalServerPort int port;

  @Autowired SchoolRepository schoolRepository;
  @Autowired AnnouncementRepository announcementRepository;
  @Autowired AnnouncementRecipientRepository recipientRepository;
  @Autowired UserRepository userRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
  @Autowired StringRedisTemplate redis;
  @Autowired WhatsAppProperties properties;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID recipientId;
  private String wamid;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    // Clean every test for isolation
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> announcementRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());
    java.util.Set<String> idemKeys = redis.keys("idempotency:whatsapp:*");
    if (idemKeys != null && !idemKeys.isEmpty()) {
      redis.delete(idemKeys);
    }

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Webhook Test School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());

    wamid = "wamid.WEBHOOK-" + UUID.randomUUID();

    TenantContext.set(schoolId);
    try {
      UUID senderId =
          tx.execute(
                  s ->
                      userRepository.save(
                          User.staff(
                              schoolId,
                              UserRole.SCHOOL_ADMIN,
                              "Sender",
                              "sender@webhook.test",
                              passwordEncoder.encode("pass"))))
              .getId();
      UUID parentId =
          tx.execute(
                  s ->
                      userRepository.save(
                          User.parent(
                              schoolId,
                              "Parent",
                              "+201000099999",
                              blindIndex.hash("+201000099999"))))
              .getId();
      UUID studentId =
          tx.execute(
                  s ->
                      studentRepository.save(
                          new Student(schoolId, "Child", java.time.LocalDate.of(2015, 1, 1), null)))
              .getId();

      UUID announcementId =
          tx.execute(
                  s ->
                      announcementRepository.save(
                          new Announcement(
                              schoolId,
                              senderId,
                              AnnouncementScope.SCHOOL,
                              null,
                              Language.AR,
                              "Webhook fixture",
                              null,
                              false,
                              null,
                              AnnouncementStatus.SENT)))
              .getId();

      AnnouncementRecipient recipient =
          new AnnouncementRecipient(schoolId, announcementId, parentId, studentId);
      // Simulate the consumer having already dispatched: SENT + messageId set.
      recipient.markSent(wamid);
      recipientId = tx.execute(s -> recipientRepository.save(recipient).getId());
    } finally {
      TenantContext.clear();
    }
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void getSubscribe_withMatchingVerifyToken_returns200AndChallenge() {
    given()
        .queryParam("hub.mode", "subscribe")
        .queryParam("hub.verify_token", properties.getVerifyToken())
        .queryParam("hub.challenge", "challenge-42")
        .when()
        .get("/integrations/whatsapp/webhook")
        .then()
        .statusCode(200)
        .body(equalTo("challenge-42"));
  }

  @Test
  void getSubscribe_withWrongVerifyToken_returns403() {
    given()
        .queryParam("hub.mode", "subscribe")
        .queryParam("hub.verify_token", "totally-wrong")
        .queryParam("hub.challenge", "challenge-x")
        .when()
        .get("/integrations/whatsapp/webhook")
        .then()
        .statusCode(403);
  }

  @Test
  void post_withValidSignature_updatesRecipientDeliveryStatus() {
    String body = deliveryStatusBody(wamid, "delivered");
    String signature =
        "sha256="
            + WebhookSignatureVerifier.hmacSha256Hex(
                body.getBytes(StandardCharsets.UTF_8), properties.getAppSecret());

    given()
        .contentType(ContentType.JSON)
        .header("X-Hub-Signature-256", signature)
        .body(body)
        .when()
        .post("/integrations/whatsapp/webhook")
        .then()
        .statusCode(200);

    TenantContext.set(schoolId);
    DeliveryStatus actual =
        tx.execute(
            s -> recipientRepository.findById(recipientId).orElseThrow().getDeliveryStatus());
    assertThat(actual).isEqualTo(DeliveryStatus.DELIVERED);
  }

  @Test
  void post_withMissingSignature_returns401() {
    String body = deliveryStatusBody(wamid, "delivered");

    given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/integrations/whatsapp/webhook")
        .then()
        .statusCode(401);

    TenantContext.set(schoolId);
    DeliveryStatus actual =
        tx.execute(
            s -> recipientRepository.findById(recipientId).orElseThrow().getDeliveryStatus());
    assertThat(actual)
        .as("missing signature must not mutate any recipient")
        .isEqualTo(DeliveryStatus.SENT);
  }

  @Test
  void post_withInvalidSignature_returns401() {
    String body = deliveryStatusBody(wamid, "delivered");

    given()
        .contentType(ContentType.JSON)
        .header("X-Hub-Signature-256", "sha256=deadbeef")
        .body(body)
        .when()
        .post("/integrations/whatsapp/webhook")
        .then()
        .statusCode(401);
  }

  @Test
  void post_duplicateDelivery_isIdempotent() {
    String body = deliveryStatusBody(wamid, "delivered");
    String signature =
        "sha256="
            + WebhookSignatureVerifier.hmacSha256Hex(
                body.getBytes(StandardCharsets.UTF_8), properties.getAppSecret());

    given()
        .contentType(ContentType.JSON)
        .header("X-Hub-Signature-256", signature)
        .body(body)
        .when()
        .post("/integrations/whatsapp/webhook")
        .then()
        .statusCode(200);

    // Second identical delivery — the dedupe key must drop it before any mutation runs. Even after
    // we attempt to downgrade by manually changing the recipient back to SENT, the second POST must
    // NOT re-advance the row (proving the SETNX guard short-circuited).
    TenantContext.set(schoolId);
    tx.executeWithoutResult(
        s -> {
          AnnouncementRecipient recipient = recipientRepository.findById(recipientId).orElseThrow();
          recipient.markSent(wamid); // reset to SENT
        });
    TenantContext.clear();

    given()
        .contentType(ContentType.JSON)
        .header("X-Hub-Signature-256", signature)
        .body(body)
        .when()
        .post("/integrations/whatsapp/webhook")
        .then()
        .statusCode(200);

    TenantContext.set(schoolId);
    DeliveryStatus actual =
        tx.execute(
            s -> recipientRepository.findById(recipientId).orElseThrow().getDeliveryStatus());
    assertThat(actual)
        .as("duplicate delivery must not re-advance the recipient")
        .isEqualTo(DeliveryStatus.SENT);
  }

  private static String deliveryStatusBody(String wamid, String status) {
    return """
        {
          "object": "whatsapp_business_account",
          "entry": [{
            "id": "WBA",
            "changes": [{
              "field": "messages",
              "value": {
                "messaging_product": "whatsapp",
                "statuses": [{
                  "id": "%s",
                  "status": "%s",
                  "timestamp": "1700000000",
                  "recipient_id": "201234567890"
                }]
              }
            }]
          }]
        }
        """
        .formatted(wamid, status);
  }
}
