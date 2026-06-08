package com.schoolbridge.api.announcements;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.announcements.dto.AnnouncementResponse;
import com.schoolbridge.api.announcements.dto.CreateAnnouncementRequest;
import com.schoolbridge.api.announcements.enums.AnnouncementScope;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.announcements.service.AnnouncementService;
import com.schoolbridge.api.classes.RelationshipType;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.common.outbox.OutboxRepository;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.otp.OtpService;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import io.restassured.RestAssured;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end test for the parent acknowledgement flow + {@code @perms.parentReceivedAnnouncement}.
 * A parent on the recipient list can acknowledge; a parent not on it gets a 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnnouncementParentAckIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort int port;

  @Autowired AnnouncementService announcementService;
  @Autowired AnnouncementRepository announcementRepository;
  @Autowired AnnouncementRecipientRepository recipientRepository;
  @Autowired ParentStudentLinkRepository linkRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired OutboxRepository outboxRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired OtpService otpService;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID senderId;
  private UUID recipientParentId;
  private UUID outsiderParentId;
  private String recipientToken;
  private String outsiderToken;
  private UUID announcementId;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> announcementRepository.deleteAll());
    tx.executeWithoutResult(s -> outboxRepository.deleteAll());
    tx.executeWithoutResult(s -> linkRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Ack School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());

    senderId =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.staff(
                            schoolId,
                            UserRole.SCHOOL_ADMIN,
                            "Admin",
                            "admin@ack.test",
                            passwordEncoder.encode("pass")))
                    .getId());

    recipientParentId = seedParentWithChild("+201090000001");
    outsiderParentId = seedParentWithoutChild("+201090000002");

    recipientToken = issueParentToken(recipientParentId);
    outsiderToken = issueParentToken(outsiderParentId);

    TenantContext.set(schoolId);
    AnnouncementResponse created =
        tx.execute(
            s ->
                announcementService.create(
                    schoolId,
                    senderId,
                    new CreateAnnouncementRequest(
                        AnnouncementScope.SCHOOL,
                        null,
                        null,
                        null,
                        Language.AR,
                        "إعلان للتأكيد",
                        null,
                        true,
                        null)));
    announcementId = created.id();
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void recipient_acknowledges_returns204AndPersistsTimestamp() {
    given()
        .header("Authorization", "Bearer " + recipientToken)
        .when()
        .post("/api/v1/announcements/" + announcementId + "/acknowledge")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(204);

    TenantContext.set(schoolId);
    var stored =
        tx.execute(
            s ->
                recipientRepository.findFirstByAnnouncementIdAndParentUserId(
                    announcementId, recipientParentId));
    assertThat(stored).isPresent();
    assertThat(stored.get().getAcknowledgedAt()).as("ack timestamp must be set").isNotNull();
  }

  @Test
  void nonRecipient_acknowledge_returns403() {
    given()
        .header("Authorization", "Bearer " + outsiderToken)
        .when()
        .post("/api/v1/announcements/" + announcementId + "/acknowledge")
        .then()
        .statusCode(403);
  }

  private UUID seedParentWithChild(String phone) {
    UUID parentId =
        tx.execute(
            s ->
                userRepository
                    .save(User.parent(schoolId, "Parent " + phone, phone, blindIndex.hash(phone)))
                    .getId());
    UUID studentId =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolId, "Child " + phone, LocalDate.of(2015, 1, 1), null))
                    .getId());
    tx.executeWithoutResult(
        s ->
            linkRepository.save(
                new ParentStudentLink(
                    schoolId, parentId, studentId, RelationshipType.MOTHER, true)));
    return parentId;
  }

  private UUID seedParentWithoutChild(String phone) {
    return tx.execute(
        s ->
            userRepository
                .save(User.parent(schoolId, "Parent " + phone, phone, blindIndex.hash(phone)))
                .getId());
  }

  private String issueParentToken(UUID parentId) {
    OtpService.IssuedOtp ticket = otpService.issue(parentId, schoolId);
    OtpService.ParentSession session = otpService.verify(ticket.ticketId(), ticket.code());
    return session.token();
  }
}
