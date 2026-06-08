package com.schoolbridge.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.schoolbridge.api.integrations.rabbit.AttendanceAlertConsumer;
import com.schoolbridge.api.integrations.whatsapp.FakeWhatsAppClient;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
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
 * Cross-tenant safety for the attendance alert path. An {@code attendance.absent_alert} event for
 * school A must touch only school A's records and recipients — the consumer's {@link
 * TenantContext#runAs} wrap activates Hibernate's {@code tenantFilter} so any inadvertent query in
 * {@link AttendanceAlertService} reading another school's rows is invisible.
 */
@SpringBootTest
class AttendanceAlertConsumerCrossTenantIsolationTest extends AbstractIntegrationTest {

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
  @Autowired ObjectMapper objectMapper;
  @Autowired MeterRegistry meterRegistry;
  @Autowired TransactionTemplate tx;

  private AttendanceAlertConsumer consumer;

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
    consumer = new AttendanceAlertConsumer(alertService, objectMapper, meterRegistry);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void runAsSchoolA_doesNotMaterializeRecipientsForSchoolB() throws Exception {
    UUID schoolA = seedSchool("Iso A");
    UUID schoolB = seedSchool("Iso B");

    UUID teacherA = seedTeacher(schoolA, "teacher-iso@a.test");
    UUID teacherB = seedTeacher(schoolB, "teacher-iso@b.test");
    UUID studentA = seedStudent(schoolA, "StudentA Adam");
    UUID studentB = seedStudent(schoolB, "StudentB Bara");
    UUID classA = seedClass(schoolA);
    UUID classB = seedClass(schoolB);

    UUID recordA = seedAbsent(schoolA, studentA, classA, teacherA);
    UUID recordB = seedAbsent(schoolB, studentB, classB, teacherB);

    // 2 parents per school, all linked to their respective student.
    seedLinked(schoolA, studentA, "+201090A00001");
    seedLinked(schoolA, studentA, "+201090A00002");
    seedLinked(schoolB, studentB, "+201090B00001");
    seedLinked(schoolB, studentB, "+201090B00002");

    byte[] payloadA = payload(schoolA, recordA, studentA, classA, Instant.now());
    consumer.handle(payloadA, AttendanceStatus.ABSENT);

    assertThat(fakeWhatsApp.sent()).as("only school A's two parents must be dispatched").hasSize(2);

    TenantContext.set(schoolA);
    List<AttendanceAlertRecipient> aRecipients =
        tx.execute(s -> recipientRepository.findAllByAttendanceRecordId(recordA));
    TenantContext.clear();
    assertThat(aRecipients).hasSize(2);

    TenantContext.set(schoolB);
    List<AttendanceAlertRecipient> bRecipients =
        tx.execute(s -> recipientRepository.findAllByAttendanceRecordId(recordB));
    AttendanceRecord bRecord = tx.execute(s -> recordRepository.findById(recordB).orElseThrow());
    TenantContext.clear();
    assertThat(bRecipients)
        .as("school B's record must have NO materialized alert recipients")
        .isEmpty();
    assertThat(bRecord.getAlertSentAt())
        .as("school B's alertSentAt must remain null — A's consumer must not touch it")
        .isNull();
  }

  // --- helpers -------------------------------------------------------------

  private byte[] payload(
      UUID schoolId, UUID recordId, UUID studentId, UUID classId, Instant markedAt)
      throws Exception {
    Map<String, Object> payload = new HashMap<>();
    payload.put("schoolId", schoolId.toString());
    payload.put("recordId", recordId.toString());
    payload.put("studentId", studentId.toString());
    payload.put("classId", classId.toString());
    payload.put("date", LocalDate.of(2026, 5, 31).toString());
    payload.put("status", "ABSENT");
    payload.put("markedAt", markedAt.toString());
    payload.put("traceId", null);
    return objectMapper.writeValueAsBytes(payload);
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

  private UUID seedClass(UUID schoolId) {
    return tx.execute(
        s ->
            classRepository
                .save(new SchoolClass(schoolId, "Cls", "Grade 3", "2025-2026", null))
                .getId());
  }

  private UUID seedAbsent(UUID schoolId, UUID studentId, UUID classId, UUID teacherId) {
    return tx.execute(
        s ->
            recordRepository
                .save(
                    new AttendanceRecord(
                        schoolId,
                        studentId,
                        classId,
                        LocalDate.of(2026, 5, 31),
                        AttendanceStatus.ABSENT,
                        teacherId,
                        Instant.now()))
                .getId());
  }

  private UUID seedLinked(UUID schoolId, UUID studentId, String phone) {
    UUID parentId =
        tx.execute(
            s ->
                userRepository
                    .save(User.parent(schoolId, "P " + phone, phone, blindIndex.hash(phone)))
                    .getId());
    tx.executeWithoutResult(
        s ->
            linkRepository.save(
                new ParentStudentLink(
                    schoolId, parentId, studentId, RelationshipType.MOTHER, true)));
    return parentId;
  }
}
