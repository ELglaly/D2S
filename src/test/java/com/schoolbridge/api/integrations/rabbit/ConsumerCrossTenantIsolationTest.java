package com.schoolbridge.api.integrations.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.schoolbridge.api.integrations.AnnouncementSendService;
import com.schoolbridge.api.integrations.whatsapp.FakeWhatsAppClient;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cross-tenant safety: an {@code announcement.created} event for school A must only touch school
 * A's recipients even though the consumer runs without an inbound HTTP {@link TenantContext}.
 *
 * <p>This proves that the consumer's {@code TenantContext.runAs(schoolId, ...)} wrap activates the
 * Hibernate {@code tenantFilter} on every load/save in {@code AnnouncementSendService}, so the
 * filter excludes school B's rows from any query the dispatcher path makes.
 */
@SpringBootTest
class ConsumerCrossTenantIsolationTest extends AbstractIntegrationTest {

  @Autowired AnnouncementSendService sendService;
  @Autowired AnnouncementRepository announcementRepository;
  @Autowired AnnouncementRecipientRepository recipientRepository;
  @Autowired UserRepository userRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired FakeWhatsAppClient fakeWhatsApp;
  @Autowired TransactionTemplate tx;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> announcementRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());
    fakeWhatsApp.reset();
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void runAsSchoolA_doesNotDispatchSchoolBsRecipients() {
    UUID schoolAId = seedSchool("School A");
    UUID schoolBId = seedSchool("School B");

    UUID senderA = seedSender(schoolAId, "admin-a@xt.test");
    UUID senderB = seedSender(schoolBId, "admin-b@xt.test");

    UUID announcementA = seedAnnouncement(schoolAId, senderA, "body for A");
    UUID announcementB = seedAnnouncement(schoolBId, senderB, "body for B");

    seedRecipient(schoolAId, announcementA, "+201000700001");
    seedRecipient(schoolAId, announcementA, "+201000700002");
    seedRecipient(schoolBId, announcementB, "+201000700003");
    seedRecipient(schoolBId, announcementB, "+201000700004");

    // Dispatch for school A only.
    TenantContext.runAs(
        schoolAId,
        () -> {
          sendService.dispatchCreated(announcementA, Language.AR, "body for A");
          return null;
        });

    // School A recipients should be SENT; school B recipients still QUEUED — proves the filter
    // held.
    TenantContext.set(schoolAId);
    List<AnnouncementRecipient> aRecipients =
        tx.execute(
            s ->
                recipientRepository
                    .findAllByAnnouncementId(
                        announcementA, org.springframework.data.domain.Pageable.unpaged())
                    .getContent());
    TenantContext.clear();
    assertThat(aRecipients).hasSize(2);
    assertThat(aRecipients)
        .allSatisfy(r -> assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT));

    TenantContext.set(schoolBId);
    List<AnnouncementRecipient> bRecipients =
        tx.execute(
            s ->
                recipientRepository
                    .findAllByAnnouncementId(
                        announcementB, org.springframework.data.domain.Pageable.unpaged())
                    .getContent());
    TenantContext.clear();
    assertThat(bRecipients).hasSize(2);
    assertThat(bRecipients)
        .as("school B recipients must remain QUEUED — A's consumer must not touch them")
        .allSatisfy(r -> assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.QUEUED));

    // And the fake adapter must have received exactly 2 sends, all for A's phone range.
    assertThat(fakeWhatsApp.sent()).hasSize(2);
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
                        UserRole.SCHOOL_ADMIN,
                        "Sender",
                        email,
                        passwordEncoder.encode("pass"))))
        .getId();
  }

  private UUID seedAnnouncement(UUID schoolId, UUID senderId, String body) {
    return TenantContext.runAs(
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
                                body,
                                null,
                                false,
                                null,
                                AnnouncementStatus.SENT)))
                .getId());
  }

  private void seedRecipient(UUID schoolId, UUID announcementId, String parentPhone) {
    UUID parentId =
        tx.execute(
                s ->
                    userRepository.save(
                        User.parent(
                            schoolId,
                            "Parent " + parentPhone,
                            parentPhone,
                            blindIndex.hash(parentPhone))))
            .getId();
    UUID studentId =
        tx.execute(
                s ->
                    studentRepository.save(
                        new Student(
                            schoolId, "Kid " + parentPhone, LocalDate.of(2015, 1, 1), null)))
            .getId();
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
}
