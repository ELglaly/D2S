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
  private UUID twoChildParentId;
  private String recipientToken;
  private String outsiderToken;
  private String twoChildToken;
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

    recipientParentId = seedParentWithChildren("+201090000001", 1);
    outsiderParentId = seedParentWithoutChild("+201090000002");
    // Seeded before the announcement is created, since recipients are materialised at create time.
    twoChildParentId = seedParentWithChildren("+201090000003", 2);

    recipientToken = issueParentToken(recipientParentId);
    outsiderToken = issueParentToken(outsiderParentId);
    twoChildToken = issueParentToken(twoChildParentId);

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
                recipientRepository.findAllByAnnouncementIdAndParentUserId(
                    announcementId, recipientParentId));
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0).getAcknowledgedAt()).as("ack timestamp must be set").isNotNull();
  }

  /**
   * Regression: a parent with two children in scope holds two recipient rows but sees one
   * announcement and taps acknowledge once. Before this fix {@code acknowledge} used a {@code
   * findFirst...} lookup, so the sibling row kept a null {@code acknowledged_at} forever and the
   * school's acknowledgement report under-counted with no way for the parent to correct it.
   */
  @Test
  void parentWithTwoChildren_singleAcknowledge_clearsEveryRecipientRow() {
    TenantContext.set(schoolId);
    var before =
        tx.execute(
            s ->
                recipientRepository.findAllByAnnouncementIdAndParentUserId(
                    announcementId, twoChildParentId));
    assertThat(before).as("fan-out must produce one row per linked child").hasSize(2);
    TenantContext.clear();

    given()
        .header("Authorization", "Bearer " + twoChildToken)
        .when()
        .post("/api/v1/announcements/" + announcementId + "/acknowledge")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(204);

    TenantContext.set(schoolId);
    var after =
        tx.execute(
            s ->
                recipientRepository.findAllByAnnouncementIdAndParentUserId(
                    announcementId, twoChildParentId));
    assertThat(after).hasSize(2);
    assertThat(after)
        .as("one tap must acknowledge every child's row, not just the first")
        .allSatisfy(r -> assertThat(r.getAcknowledgedAt()).isNotNull());
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

  private UUID seedParentWithChildren(String phone, int childCount) {
    UUID parentId =
        tx.execute(
            s ->
                userRepository
                    .save(User.parent(schoolId, "Parent " + phone, phone, blindIndex.hash(phone)))
                    .getId());
    for (int i = 0; i < childCount; i++) {
      String childName = "Child " + phone + "-" + i;
      UUID studentId =
          tx.execute(
              s ->
                  studentRepository
                      .save(new Student(schoolId, childName, LocalDate.of(2015, 1, 1), null))
                      .getId());
      tx.executeWithoutResult(
          s ->
              linkRepository.save(
                  new ParentStudentLink(
                      schoolId, parentId, studentId, RelationshipType.MOTHER, true)));
    }
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
