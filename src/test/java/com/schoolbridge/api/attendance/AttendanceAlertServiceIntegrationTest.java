package com.schoolbridge.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.RelationshipType;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.entity.Student;
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
 * End-to-end coverage of {@link AttendanceAlertService} against the real Postgres + fake adapter
 * stack: fan-out shape, status-specific template selection, quiet-hours deferral, recipient
 * idempotency on redelivery, and {@code AttendanceRecord.alertSentAt} stamping.
 *
 * <p>The relay is off in the default test profile, so the service is exercised directly without a
 * Rabbit round-trip — same approach as {@code AnnouncementCreatedConsumerIntegrationTest}.
 */
@SpringBootTest
class AttendanceAlertServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired AttendanceAlertService alertService;
  @Autowired AttendanceRecordRepository recordRepository;
  @Autowired AttendanceAlertRecipientRepository recipientRepository;
  @Autowired ParentStudentLinkRepository linkRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired FakeWhatsAppClient fakeWhatsApp;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID teacherId;
  private UUID studentId;
  private UUID classId;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> recordRepository.deleteAll());
    tx.executeWithoutResult(s -> linkRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());
    fakeWhatsApp.reset();
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void fansOutToEveryLinkedParent_marksRecipientsSent_setsAlertSentAt() {
    schoolId = seedSchool("Fanout School", SchoolSettings.defaults());
    teacherId = seedTeacher(schoolId, "teacher@fanout.test");
    studentId = seedStudent(schoolId, "Adam Khaled");
    classId = seedClass(schoolId, "3A");
    UUID recordId = seedAbsentRecord(AttendanceStatus.ABSENT);
    seedLinkedParent("+201090300001");
    seedLinkedParent("+201090300002");
    seedLinkedParent("+201090300003");

    TenantContext.runAs(
        schoolId,
        () -> {
          alertService.dispatchAlert(recordId, AttendanceStatus.ABSENT);
          return null;
        });

    assertThat(fakeWhatsApp.sent()).hasSize(3);
    assertThat(fakeWhatsApp.sent())
        .allSatisfy(
            sent -> {
              assertThat(sent.templateName()).isEqualTo("attendance_absent_v1");
              assertThat(sent.params()).hasSize(2);
              // First name = first whitespace-separated token of the encrypted full name.
              assertThat(sent.params().get(0).text()).isEqualTo("Adam");
            });

    TenantContext.set(schoolId);
    List<AttendanceAlertRecipient> rows =
        tx.execute(s -> recipientRepository.findAllByAttendanceRecordId(recordId));
    assertThat(rows).hasSize(3);
    assertThat(rows)
        .allSatisfy(
            r -> {
              assertThat(r.getDeliveryStatus()).isEqualTo(AttendanceAlertStatus.SENT);
              assertThat(r.getMessageId()).isNotNull();
            });
    AttendanceRecord stamped = tx.execute(s -> recordRepository.findById(recordId).orElseThrow());
    assertThat(stamped.getAlertSentAt()).as("alertSentAt must close the NFR-P2 window").isNotNull();
    TenantContext.clear();
  }

  @Test
  void quietHoursOn_andInWindow_defersInsteadOfDispatching() {
    SchoolSettings nearlyAlwaysQuiet =
        new SchoolSettings(
            Language.EN,
            LocalTime.of(0, 0),
            LocalTime.of(23, 59),
            true,
            LocalTime.of(19, 0),
            List.of(-7, -1, 0, 7),
            null,
            false,
            /* alertsRespectQuietHours */ true,
            /* rosterDueByLocalTime */ LocalTime.of(9, 0));
    schoolId = seedSchool("Quiet School", nearlyAlwaysQuiet);
    teacherId = seedTeacher(schoolId, "teacher@quiet.test");
    studentId = seedStudent(schoolId, "Sara");
    classId = seedClass(schoolId, "4A");
    UUID recordId = seedAbsentRecord(AttendanceStatus.ABSENT);
    seedLinkedParent("+201090400001");
    seedLinkedParent("+201090400002");

    TenantContext.runAs(
        schoolId,
        () -> {
          alertService.dispatchAlert(recordId, AttendanceStatus.ABSENT);
          return null;
        });

    assertThat(fakeWhatsApp.sent())
        .as("quiet hours active: no WhatsApp send should fire on the immediate path")
        .isEmpty();
    TenantContext.set(schoolId);
    List<AttendanceAlertRecipient> rows =
        tx.execute(s -> recipientRepository.findAllByAttendanceRecordId(recordId));
    assertThat(rows).hasSize(2);
    assertThat(rows)
        .allSatisfy(
            r -> {
              assertThat(r.getDeliveryStatus()).isEqualTo(AttendanceAlertStatus.DEFERRED);
              assertThat(r.getDeferredUntil()).isNotNull();
              assertThat(r.getMessageId()).isNull();
            });
    AttendanceRecord stamped = tx.execute(s -> recordRepository.findById(recordId).orElseThrow());
    assertThat(stamped.getAlertSentAt())
        .as("alertSentAt must stay null until the sweeper releases")
        .isNull();
    TenantContext.clear();
  }

  @Test
  void statusSpecificTemplateNames_areSelectedByTriggeringStatus() {
    schoolId = seedSchool("Templates School", SchoolSettings.defaults());
    teacherId = seedTeacher(schoolId, "teacher@templates.test");
    studentId = seedStudent(schoolId, "Yara");
    classId = seedClass(schoolId, "5A");
    UUID record = seedAbsentRecord(AttendanceStatus.LATE);
    seedLinkedParent("+201090500001");

    TenantContext.runAs(
        schoolId,
        () -> {
          alertService.dispatchAlert(record, AttendanceStatus.LATE);
          return null;
        });

    assertThat(fakeWhatsApp.sent()).hasSize(1);
    assertThat(fakeWhatsApp.sent().get(0).templateName()).isEqualTo("attendance_late_v1");
  }

  @Test
  void redelivery_isIdempotent_doesNotDoubleDispatch() {
    schoolId = seedSchool("Idempotent School", SchoolSettings.defaults());
    teacherId = seedTeacher(schoolId, "teacher@idemp.test");
    studentId = seedStudent(schoolId, "Tariq");
    classId = seedClass(schoolId, "6A");
    UUID recordId = seedAbsentRecord(AttendanceStatus.ABSENT);
    seedLinkedParent("+201090600001");
    seedLinkedParent("+201090600002");

    TenantContext.runAs(
        schoolId,
        () -> {
          alertService.dispatchAlert(recordId, AttendanceStatus.ABSENT);
          alertService.dispatchAlert(recordId, AttendanceStatus.ABSENT);
          return null;
        });

    assertThat(fakeWhatsApp.sent())
        .as("redelivery must not double-dispatch SENT recipients")
        .hasSize(2);
  }

  @Test
  void parentWithoutPhone_isMarkedFailed_othersDispatched() {
    schoolId = seedSchool("No-Phone School", SchoolSettings.defaults());
    teacherId = seedTeacher(schoolId, "teacher@nophone.test");
    studentId = seedStudent(schoolId, "Lara");
    classId = seedClass(schoolId, "7A");
    UUID recordId = seedAbsentRecord(AttendanceStatus.ABSENT);

    // Parent with phone.
    seedLinkedParent("+201090700001");
    // Parent without phone (created directly to bypass the parent factory's phone requirement).
    UUID phonelessParentId =
        tx.execute(
            s ->
                userRepository
                    .save(User.parent(schoolId, "Phoneless", "+201090700002", null))
                    .getId());
    tx.executeWithoutResult(
        s ->
            linkRepository.save(
                new ParentStudentLink(
                    schoolId, phonelessParentId, studentId, RelationshipType.FATHER, false)));
    // Then null out the phone.
    tx.executeWithoutResult(
        s ->
            userRepository
                .findById(phonelessParentId)
                .ifPresent(
                    u -> {
                      try {
                        var f = User.class.getDeclaredField("phone");
                        f.setAccessible(true);
                        f.set(u, null);
                      } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                      }
                    }));

    TenantContext.runAs(
        schoolId,
        () -> {
          alertService.dispatchAlert(recordId, AttendanceStatus.ABSENT);
          return null;
        });

    assertThat(fakeWhatsApp.sent()).as("only the parent with a phone should be sent to").hasSize(1);
    TenantContext.set(schoolId);
    List<AttendanceAlertRecipient> rows =
        tx.execute(s -> recipientRepository.findAllByAttendanceRecordId(recordId));
    assertThat(rows).hasSize(2);
    assertThat(rows.stream().map(AttendanceAlertRecipient::getDeliveryStatus).toList())
        .containsExactlyInAnyOrder(AttendanceAlertStatus.SENT, AttendanceAlertStatus.FAILED);
    TenantContext.clear();
  }

  // --- helpers -------------------------------------------------------------

  private UUID seedSchool(String name, SchoolSettings settings) {
    return tx.execute(
        s ->
            schoolRepository
                .save(
                    new School(
                        name, "EG", "Africa/Cairo", "ar-EG", SubscriptionTier.STANDARD, settings))
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

  private UUID seedStudent(UUID schoolId, String fullName) {
    return tx.execute(
        s ->
            studentRepository
                .save(new Student(schoolId, fullName, LocalDate.of(2015, 1, 1), null))
                .getId());
  }

  private UUID seedClass(UUID schoolId, String name) {
    return tx.execute(
        s ->
            classRepository
                .save(new SchoolClass(schoolId, name, "Grade 3", "2025-2026", null))
                .getId());
  }

  private UUID seedAbsentRecord(AttendanceStatus status) {
    return tx.execute(
        s ->
            recordRepository
                .save(
                    new AttendanceRecord(
                        schoolId,
                        studentId,
                        classId,
                        LocalDate.of(2026, 5, 31),
                        status,
                        teacherId,
                        Instant.now()))
                .getId());
  }

  private UUID seedLinkedParent(String phone) {
    UUID parentId =
        tx.execute(
            s ->
                userRepository
                    .save(User.parent(schoolId, "Parent " + phone, phone, blindIndex.hash(phone)))
                    .getId());
    tx.executeWithoutResult(
        s ->
            linkRepository.save(
                new ParentStudentLink(
                    schoolId, parentId, studentId, RelationshipType.MOTHER, true)));
    return parentId;
  }
}
