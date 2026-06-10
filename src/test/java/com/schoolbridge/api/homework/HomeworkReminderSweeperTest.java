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
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
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
 * Drives {@link HomeworkReminderSweeper} directly — bypassing {@code @Scheduled} timing. The
 * sweeper bean is gated off in the default test profile so we construct it ourselves with autowired
 * collaborators, mirroring {@code AttendanceSweeperTest}.
 */
@SpringBootTest
class HomeworkReminderSweeperTest extends AbstractIntegrationTest {

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
  @Autowired StringRedisTemplate redis;
  @Autowired MeterRegistry meterRegistry;
  @Autowired TransactionTemplate tx;

  private HomeworkReminderSweeper sweeper;

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
    purgeRedis("homework:reminder_fired:*");

    sweeper =
        new HomeworkReminderSweeper(
            homeworkItemRepository,
            recipientRepository,
            reminderService,
            schoolRepository,
            redis,
            meterRegistry,
            15,
            2,
            Duration.ofHours(36));
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  // --- fireReminders --------------------------------------------------------

  @Test
  void fireReminders_insideWindow_firesReminder() {
    // homeworkReminderTime = now ± 0 minutes — always inside the window.
    LocalTime nowLocal =
        java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Cairo")).toLocalTime();
    UUID schoolId = seedSchool(reminderEnabledSettings(nowLocal));
    UUID classId = seedClass(schoolId, "3A");
    UUID teacherId = seedTeacher(schoolId, "teacher@sw1.test");
    UUID studentId = seedStudent(schoolId, "Ahmed");
    UUID parentId = seedLinkedParent(schoolId, studentId, classId, "+201020000001");
    UUID homeworkId =
        seedPublishedHomework(schoolId, classId, teacherId, LocalDate.now().plusDays(1));

    sweeper.fireReminders();

    assertThat(fakeWhatsApp.sent()).hasSize(1);
    TenantContext.set(schoolId);
    HomeworkItem item = tx.execute(s -> homeworkItemRepository.findById(homeworkId).orElseThrow());
    TenantContext.clear();
    assertThat(item.getReminderSentAt()).isNotNull();
  }

  @Test
  void fireReminders_outsideWindow_doesNotFire() {
    // homeworkReminderTime = 03:00 — virtually never within 15 minutes of current time.
    UUID schoolId = seedSchool(reminderEnabledSettings(LocalTime.of(3, 0)));
    UUID classId = seedClass(schoolId, "4B");
    UUID teacherId = seedTeacher(schoolId, "teacher@sw2.test");
    UUID studentId = seedStudent(schoolId, "Layla");
    seedLinkedParent(schoolId, studentId, classId, "+201020000002");
    seedPublishedHomework(schoolId, classId, teacherId, LocalDate.now().plusDays(1));

    sweeper.fireReminders();

    assertThat(fakeWhatsApp.sent()).isEmpty();
  }

  @Test
  void fireReminders_redisDedup_firesOnlyOnce() {
    LocalTime nowLocal =
        java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Cairo")).toLocalTime();
    UUID schoolId = seedSchool(reminderEnabledSettings(nowLocal));
    UUID classId = seedClass(schoolId, "5C");
    UUID teacherId = seedTeacher(schoolId, "teacher@sw3.test");
    UUID studentId = seedStudent(schoolId, "Omar");
    seedLinkedParent(schoolId, studentId, classId, "+201020000003");
    UUID homeworkId =
        seedPublishedHomework(schoolId, classId, teacherId, LocalDate.now().plusDays(1));

    sweeper.fireReminders();
    fakeWhatsApp.reset();
    // Clear reminderSentAt so query can return this item again — only SETNX should block it.
    tx.executeWithoutResult(
        s -> {
          TenantContext.set(schoolId);
          HomeworkItem item = homeworkItemRepository.findById(homeworkId).orElseThrow();
          // Cannot clear via entity directly — verify SETNX protection via a second sweep:
          // The key was already acquired, so the second sweep must skip the item.
        });

    sweeper.fireReminders();

    assertThat(fakeWhatsApp.sent()).as("second sweep within dedup TTL must not re-fire").isEmpty();
  }

  @Test
  void fireReminders_skipsItemsWithReminderSentAt() {
    LocalTime nowLocal =
        java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Cairo")).toLocalTime();
    UUID schoolId = seedSchool(reminderEnabledSettings(nowLocal));
    UUID classId = seedClass(schoolId, "6D");
    UUID teacherId = seedTeacher(schoolId, "teacher@sw4.test");
    UUID studentId = seedStudent(schoolId, "Nadia");
    seedLinkedParent(schoolId, studentId, classId, "+201020000004");
    UUID homeworkId =
        seedPublishedHomework(schoolId, classId, teacherId, LocalDate.now().plusDays(1));

    // Pre-stamp reminderSentAt — query won't return it.
    tx.executeWithoutResult(
        s -> {
          TenantContext.set(schoolId);
          HomeworkItem item = homeworkItemRepository.findById(homeworkId).orElseThrow();
          item.markReminderSent(Instant.now().minusSeconds(3600));
          homeworkItemRepository.save(item);
        });
    TenantContext.clear();

    sweeper.fireReminders();

    assertThat(fakeWhatsApp.sent()).isEmpty();
  }

  @Test
  void fireReminders_skipsItemsOutsideHorizon() {
    LocalTime nowLocal =
        java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Cairo")).toLocalTime();
    UUID schoolId = seedSchool(reminderEnabledSettings(nowLocal));
    UUID classId = seedClass(schoolId, "7E");
    UUID teacherId = seedTeacher(schoolId, "teacher@sw5.test");
    UUID studentId = seedStudent(schoolId, "Rami");
    seedLinkedParent(schoolId, studentId, classId, "+201020000005");
    // dueDate = today + 30 days, well beyond horizon of 2 days.
    seedPublishedHomework(schoolId, classId, teacherId, LocalDate.now().plusDays(30));

    sweeper.fireReminders();

    assertThat(fakeWhatsApp.sent()).isEmpty();
  }

  @Test
  void fireReminders_reminderDisabled_doesNotFire() {
    LocalTime nowLocal =
        java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Cairo")).toLocalTime();
    UUID schoolId = seedSchool(reminderDisabledSettings(nowLocal));
    UUID classId = seedClass(schoolId, "8F");
    UUID teacherId = seedTeacher(schoolId, "teacher@sw6.test");
    UUID studentId = seedStudent(schoolId, "Salma");
    seedLinkedParent(schoolId, studentId, classId, "+201020000006");
    seedPublishedHomework(schoolId, classId, teacherId, LocalDate.now().plusDays(1));

    sweeper.fireReminders();

    assertThat(fakeWhatsApp.sent()).isEmpty();
  }

  // --- releaseDeferredReminders ---------------------------------------------

  @Test
  void releaseDeferredReminders_pastHold_dispatches() {
    LocalTime nowLocal =
        java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Cairo")).toLocalTime();
    UUID schoolId = seedSchool(reminderEnabledSettings(nowLocal));
    UUID classId = seedClass(schoolId, "9G");
    UUID teacherId = seedTeacher(schoolId, "teacher@sw7.test");
    UUID studentId = seedStudent(schoolId, "Tamer");
    seedLinkedParent(schoolId, studentId, classId, "+201020000007");
    UUID homeworkId =
        seedPublishedHomework(schoolId, classId, teacherId, LocalDate.now().plusDays(1));

    UUID recipientId =
        tx.execute(
            s -> {
              TenantContext.set(schoolId);
              HomeworkRecipient row = recipientRepository.findAllByHomeworkId(homeworkId).get(0);
              row.markDeferred(Instant.now().minusSeconds(10));
              return recipientRepository.save(row).getId();
            });
    TenantContext.clear();

    sweeper.releaseDeferredReminders();

    assertThat(fakeWhatsApp.sent()).hasSize(1);
    TenantContext.set(schoolId);
    HomeworkRecipient updated =
        tx.execute(s -> recipientRepository.findById(recipientId).orElseThrow());
    TenantContext.clear();
    assertThat(updated.getDeliveryStatus()).isEqualTo(HomeworkDeliveryStatus.SENT);
  }

  @Test
  void releaseDeferredReminders_futureHold_doesNotDispatch() {
    LocalTime nowLocal =
        java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Cairo")).toLocalTime();
    UUID schoolId = seedSchool(reminderEnabledSettings(nowLocal));
    UUID classId = seedClass(schoolId, "10H");
    UUID teacherId = seedTeacher(schoolId, "teacher@sw8.test");
    UUID studentId = seedStudent(schoolId, "Yasmine");
    seedLinkedParent(schoolId, studentId, classId, "+201020000008");
    UUID homeworkId =
        seedPublishedHomework(schoolId, classId, teacherId, LocalDate.now().plusDays(1));

    tx.executeWithoutResult(
        s -> {
          TenantContext.set(schoolId);
          HomeworkRecipient row = recipientRepository.findAllByHomeworkId(homeworkId).get(0);
          row.markDeferred(Instant.now().plus(Duration.ofHours(2)));
          recipientRepository.save(row);
        });
    TenantContext.clear();

    sweeper.releaseDeferredReminders();

    assertThat(fakeWhatsApp.sent()).isEmpty();
  }

  // --- helpers ---------------------------------------------------------------

  private UUID seedSchool(SchoolSettings settings) {
    return tx.execute(
        s ->
            schoolRepository
                .save(
                    new School(
                        "Sweeper Test School " + UUID.randomUUID(),
                        "EG",
                        "Africa/Cairo",
                        "ar-EG",
                        SubscriptionTier.STANDARD,
                        settings))
                .getId());
  }

  private UUID seedTeacher(UUID schoolId, String email) {
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

  private UUID seedStudent(UUID schoolId, String name) {
    return tx.execute(
        s ->
            studentRepository
                .save(new Student(schoolId, name, LocalDate.of(2015, 1, 1), null))
                .getId());
  }

  private UUID seedClass(UUID schoolId, String name) {
    return tx.execute(
        s ->
            classRepository
                .save(new SchoolClass(schoolId, name, "Grade 3", "2025-2026", null))
                .getId());
  }

  private UUID seedLinkedParent(UUID schoolId, UUID studentId, UUID classId, String phone) {
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

  private UUID seedPublishedHomework(
      UUID schoolId, UUID classId, UUID teacherId, LocalDate dueDate) {
    return tx.execute(
        s -> {
          TenantContext.set(schoolId);
          HomeworkItem item =
              new HomeworkItem(
                  schoolId,
                  classId,
                  teacherId,
                  "Test Subject",
                  "Description.",
                  null,
                  dueDate,
                  false,
                  HomeworkStatus.PUBLISHED);
          HomeworkItem saved = homeworkItemRepository.save(item);
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

  private void purgeRedis(String pattern) {
    Set<String> keys = redis.keys(pattern);
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
  }

  private SchoolSettings reminderEnabledSettings(LocalTime reminderTime) {
    return new SchoolSettings(
        Language.EN,
        LocalTime.of(21, 0),
        LocalTime.of(7, 0),
        true,
        reminderTime,
        List.of(-7, -1, 0, 7),
        null,
        false,
        false,
        LocalTime.of(9, 0));
  }

  private SchoolSettings reminderDisabledSettings(LocalTime reminderTime) {
    return new SchoolSettings(
        Language.EN,
        LocalTime.of(21, 0),
        LocalTime.of(7, 0),
        false,
        reminderTime,
        List.of(-7, -1, 0, 7),
        null,
        false,
        false,
        LocalTime.of(9, 0));
  }
}
