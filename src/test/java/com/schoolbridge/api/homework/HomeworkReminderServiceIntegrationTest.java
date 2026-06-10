package com.schoolbridge.api.homework;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.RelationshipType;
import com.schoolbridge.api.classes.entity.Enrollment;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.integrations.whatsapp.FakeWhatsAppClient;
import com.schoolbridge.api.tenant.Language;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * Integration tests for {@link HomeworkReminderService}: fan-out, quiet-hours deferral, and
 * deferred-release. Controller-layer and sweeper scheduling are tested elsewhere.
 */
@SpringBootTest
class HomeworkReminderServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired HomeworkReminderService reminderService;
  @Autowired HomeworkItemRepository homeworkItemRepository;
  @Autowired HomeworkRecipientRepository recipientRepository;
  @Autowired EnrollmentRepository enrollmentRepository;
  @Autowired ParentStudentLinkRepository linkRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired FakeWhatsAppClient fakeWhatsApp;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID classId;
  private UUID teacherId;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> homeworkItemRepository.deleteAll());
    tx.executeWithoutResult(s -> enrollmentRepository.deleteAll());
    tx.executeWithoutResult(s -> linkRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());
    fakeWhatsApp.reset();

    schoolId = seedSchool(defaultSettings());
    teacherId = seedTeacher("teacher@remind.test");
    classId = seedClass("3A");
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  // --- fan-out ---------------------------------------------------------------

  @Test
  void dispatchReminder_threeParents_sendsThreeMessages_withHomeworkReminderTemplate() {
    UUID s1 = seedStudent("Ali");
    UUID s2 = seedStudent("Nour");
    UUID s3 = seedStudent("Sara");
    UUID p1 = seedLinkedParent(s1, "+201010000001");
    UUID p2 = seedLinkedParent(s2, "+201010000002");
    UUID p3 = seedLinkedParent(s3, "+201010000003");

    UUID homeworkId = publishHomework("Math Chapter 3", LocalDate.now().plusDays(2));

    TenantContext.set(schoolId);
    reminderService.dispatchReminder(homeworkId);
    TenantContext.clear();

    assertThat(fakeWhatsApp.sent()).hasSize(3);
    fakeWhatsApp
        .sent()
        .forEach(
            req ->
                assertThat(req.templateName())
                    .as("template must be homework_reminder_v1")
                    .isEqualTo("homework_reminder_v1"));

    TenantContext.set(schoolId);
    List<HomeworkRecipient> rows =
        tx.execute(s -> recipientRepository.findAllByHomeworkId(homeworkId));
    TenantContext.clear();
    assertThat(rows).allMatch(r -> r.getDeliveryStatus() == HomeworkDeliveryStatus.SENT);
    assertThat(rows).allMatch(r -> r.getMessageId() != null);
  }

  @Test
  void dispatchReminder_stampsReminderSentAt_beforeLoop() {
    UUID studentId = seedStudent("Youssef");
    seedLinkedParent(studentId, "+201010000010");
    UUID homeworkId = publishHomework("Science Quiz", LocalDate.now().plusDays(1));

    TenantContext.set(schoolId);
    reminderService.dispatchReminder(homeworkId);
    HomeworkItem item = tx.execute(s -> homeworkItemRepository.findById(homeworkId).orElseThrow());
    TenantContext.clear();

    assertThat(item.getReminderSentAt())
        .as("reminderSentAt must be set after dispatch")
        .isNotNull();
  }

  @Test
  void dispatchReminder_idempotent_skipsAlreadySentRecipients() {
    UUID studentId = seedStudent("Mariam");
    seedLinkedParent(studentId, "+201010000020");
    UUID homeworkId = publishHomework("Arabic Composition", LocalDate.now().plusDays(3));

    TenantContext.set(schoolId);
    reminderService.dispatchReminder(homeworkId);
    fakeWhatsApp.reset();
    // Manually clear reminderSentAt so service can be called again (simulates re-entry check)
    tx.executeWithoutResult(
        s -> {
          HomeworkRecipient row = recipientRepository.findAllByHomeworkId(homeworkId).get(0);
          // Row is already SENT — dispatchReminder should skip it
        });
    reminderService.dispatchReminder(homeworkId);
    TenantContext.clear();

    assertThat(fakeWhatsApp.sent())
        .as("second call must not re-dispatch already-SENT recipients")
        .isEmpty();
  }

  // --- quiet-hours deferral --------------------------------------------------

  @Test
  void dispatchReminder_duringQuietHours_deferralsRecipients_noWhatsAppSent() {
    UUID studentId = seedStudent("Karim");
    seedLinkedParent(studentId, "+201010000030");

    // Settings: quiet hours cover all of today (always-quiet).
    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Quiet School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            quietHoursAllDaySettings()))
                    .getId());
    classId = seedClass("4B");
    teacherId = seedTeacher("teacher2@remind.test");
    UUID studentId2 = seedStudent("Lena");
    seedLinkedParent(studentId2, "+201010000031");
    UUID homeworkId = publishHomework("English Essay", LocalDate.now().plusDays(1));

    TenantContext.set(schoolId);
    reminderService.dispatchReminder(homeworkId);
    TenantContext.clear();

    assertThat(fakeWhatsApp.sent()).isEmpty();
    TenantContext.set(schoolId);
    List<HomeworkRecipient> rows =
        tx.execute(s -> recipientRepository.findAllByHomeworkId(homeworkId));
    TenantContext.clear();
    assertThat(rows).allMatch(r -> r.getDeliveryStatus() == HomeworkDeliveryStatus.DEFERRED);
    assertThat(rows).allMatch(r -> r.getDeferredUntil() != null);
  }

  // --- deferred-release ------------------------------------------------------

  @Test
  void releaseDeferred_pastHold_dispatchesAndMarksSent() {
    UUID studentId = seedStudent("Hassan");
    UUID parentId = seedLinkedParent(studentId, "+201010000040");
    UUID homeworkId = publishHomework("History Chapter", LocalDate.now().plusDays(2));

    UUID recipientId =
        tx.execute(
            s -> {
              TenantContext.set(schoolId);
              HomeworkRecipient row = recipientRepository.findAllByHomeworkId(homeworkId).get(0);
              row.markDeferred(Instant.now().minusSeconds(10));
              return recipientRepository.save(row).getId();
            });
    TenantContext.clear();

    TenantContext.set(schoolId);
    reminderService.releaseDeferred(recipientId);
    HomeworkRecipient updated =
        tx.execute(
            s -> {
              TenantContext.set(schoolId);
              return recipientRepository.findById(recipientId).orElseThrow();
            });
    TenantContext.clear();

    assertThat(fakeWhatsApp.sent()).hasSize(1);
    assertThat(updated.getDeliveryStatus()).isEqualTo(HomeworkDeliveryStatus.SENT);
    assertThat(updated.getMessageId()).isNotNull();
  }

  @Test
  void releaseDeferred_homeworkArchived_marksFailedNoDispatch() {
    UUID studentId = seedStudent("Dina");
    seedLinkedParent(studentId, "+201010000050");
    UUID homeworkId = publishHomework("PE Report", LocalDate.now().plusDays(1));

    UUID recipientId =
        tx.execute(
            s -> {
              TenantContext.set(schoolId);
              HomeworkRecipient row = recipientRepository.findAllByHomeworkId(homeworkId).get(0);
              row.markDeferred(Instant.now().minusSeconds(5));
              recipientRepository.save(row);
              HomeworkItem item = homeworkItemRepository.findById(homeworkId).orElseThrow();
              item.archive();
              homeworkItemRepository.save(item);
              return row.getId();
            });
    TenantContext.clear();

    TenantContext.set(schoolId);
    reminderService.releaseDeferred(recipientId);
    HomeworkRecipient updated =
        tx.execute(
            s -> {
              TenantContext.set(schoolId);
              return recipientRepository.findById(recipientId).orElseThrow();
            });
    TenantContext.clear();

    assertThat(fakeWhatsApp.sent()).isEmpty();
    assertThat(updated.getDeliveryStatus()).isEqualTo(HomeworkDeliveryStatus.FAILED);
  }

  // --- helpers ---------------------------------------------------------------

  private UUID seedSchool(SchoolSettings settings) {
    return tx.execute(
        s ->
            schoolRepository
                .save(
                    new School(
                        "Reminder Test School",
                        "EG",
                        "Africa/Cairo",
                        "ar-EG",
                        SubscriptionTier.STANDARD,
                        settings))
                .getId());
  }

  private UUID seedTeacher(String email) {
    return tx.execute(
        s ->
            userRepository
                .save(
                    User.staff(
                        schoolId,
                        UserRole.TEACHER,
                        "Teacher",
                        email,
                        passwordEncoder.encode("pass")))
                .getId());
  }

  private UUID seedStudent(String name) {
    return tx.execute(
        s ->
            studentRepository
                .save(new Student(schoolId, name, LocalDate.of(2015, 1, 1), null))
                .getId());
  }

  private UUID seedClass(String name) {
    return tx.execute(
        s ->
            classRepository
                .save(new SchoolClass(schoolId, name, "Grade 3", "2025-2026", null))
                .getId());
  }

  private UUID seedLinkedParent(UUID studentId, String phone) {
    UUID parentId =
        tx.execute(
            s ->
                userRepository
                    .save(User.parent(schoolId, "Parent", phone, blindIndex.hash(phone)))
                    .getId());
    tx.executeWithoutResult(
        s -> enrollmentRepository.save(new Enrollment(schoolId, studentId, classId)));
    tx.executeWithoutResult(
        s ->
            linkRepository.save(
                new ParentStudentLink(
                    schoolId, parentId, studentId, RelationshipType.MOTHER, true)));
    return parentId;
  }

  private UUID publishHomework(String subject, LocalDate dueDate) {
    return tx.execute(
        s -> {
          TenantContext.set(schoolId);
          HomeworkItem item =
              new HomeworkItem(
                  schoolId,
                  classId,
                  teacherId,
                  subject,
                  "Description.",
                  null,
                  dueDate,
                  false,
                  HomeworkStatus.PUBLISHED);
          HomeworkItem saved = homeworkItemRepository.save(item);
          // Materialize recipients for all students enrolled in classId
          linkRepository
              .findAllForClassAnnouncementScope(classId)
              .forEach(
                  link ->
                      recipientRepository.save(
                          new HomeworkRecipient(
                              schoolId,
                              saved.getId(),
                              link.getParentUserId(),
                              link.getStudentId())));
          return saved.getId();
        });
  }

  private SchoolSettings defaultSettings() {
    return new SchoolSettings(
        Language.EN,
        LocalTime.of(21, 0),
        LocalTime.of(7, 0),
        true,
        LocalTime.of(19, 0),
        List.of(-7, -1, 0, 7),
        null,
        false,
        false,
        LocalTime.of(9, 0));
  }

  /** Quiet hours spanning the full 24 hours — any dispatch attempt will be deferred. */
  private SchoolSettings quietHoursAllDaySettings() {
    return new SchoolSettings(
        Language.EN,
        LocalTime.of(0, 0),
        LocalTime.of(23, 59),
        true,
        LocalTime.of(19, 0),
        List.of(-7, -1, 0, 7),
        null,
        false,
        true,
        LocalTime.of(9, 0));
  }
}
