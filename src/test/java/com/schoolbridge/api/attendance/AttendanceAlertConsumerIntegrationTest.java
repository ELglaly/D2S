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
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives {@link AttendanceAlertConsumer#handle} directly with a JSON payload identical to what
 * {@code RabbitOutboxPublisher} would emit. Verifies the consumer-side responsibilities (tenant
 * binding, traceId MDC restoration, latency timer recording) without standing up RabbitMQ.
 *
 * <p>The consumer bean is gated on {@code schoolbridge.outbox.relay.enabled=true} (off in the
 * default test profile), so we instantiate it ourselves with autowired dependencies.
 */
@SpringBootTest
class AttendanceAlertConsumerIntegrationTest extends AbstractIntegrationTest {

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
    MDC.remove("traceId");
  }

  @Test
  void handle_dispatchesAndStampsAlertSentAt_andRecordsLatency() throws Exception {
    UUID schoolId = seedSchool("Consumer School A");
    UUID teacherId = seedTeacher(schoolId, "teacher-cons@a.test");
    UUID studentId = seedStudent(schoolId, "Hassan");
    UUID classId = seedClass(schoolId);
    UUID recordId = seedAbsentRecord(schoolId, studentId, classId, teacherId);
    seedLinkedParent(schoolId, studentId, "+201090800001");
    seedLinkedParent(schoolId, studentId, "+201090800002");

    Instant markedAt = Instant.now().minusSeconds(5);
    byte[] payload = absentPayloadJson(schoolId, recordId, studentId, classId, markedAt);

    Timer timer = timer();
    long countBefore = timer == null ? 0L : timer.count();

    consumer.handle(payload, AttendanceStatus.ABSENT);

    assertThat(fakeWhatsApp.sent()).hasSize(2);
    assertThat(fakeWhatsApp.sent())
        .allSatisfy(s -> assertThat(s.templateName()).isEqualTo("attendance_absent_v1"));

    TenantContext.set(schoolId);
    AttendanceRecord stamped = tx.execute(s -> recordRepository.findById(recordId).orElseThrow());
    assertThat(stamped.getAlertSentAt()).isNotNull();
    TenantContext.clear();

    Timer afterTimer = timer();
    assertThat(afterTimer).as("attendance.alert.latency timer must be registered").isNotNull();
    assertThat(afterTimer.count())
        .as("the consumer must record exactly one latency sample")
        .isEqualTo(countBefore + 1);
    double meanMs = afterTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
    assertThat(meanMs)
        .as("synthetic latency must be < 60 s — bounds the NFR-P2 p95 target")
        .isLessThan(60_000.0);
  }

  @Test
  void handle_restoresTraceId_intoMdcDuringDispatch() throws Exception {
    UUID schoolId = seedSchool("Trace School");
    UUID teacherId = seedTeacher(schoolId, "teacher-trace@x.test");
    UUID studentId = seedStudent(schoolId, "Mona");
    UUID classId = seedClass(schoolId);
    UUID recordId = seedAbsentRecord(schoolId, studentId, classId, teacherId);
    seedLinkedParent(schoolId, studentId, "+201090900001");

    String traceId = "abcdef0123456789";
    byte[] payload =
        absentPayloadJson(schoolId, recordId, studentId, classId, Instant.now(), traceId);

    consumer.handle(payload, AttendanceStatus.ABSENT);

    // After handle() returns, the MDC must be cleared (no traceId leak across requests).
    assertThat(MDC.get("traceId")).as("traceId must not leak past handle()").isNull();
  }

  private Timer timer() {
    return meterRegistry.find("attendance.alert.latency").timer();
  }

  // --- payload + seed helpers ---------------------------------------------

  private byte[] absentPayloadJson(
      UUID schoolId, UUID recordId, UUID studentId, UUID classId, Instant markedAt)
      throws Exception {
    return absentPayloadJson(schoolId, recordId, studentId, classId, markedAt, null);
  }

  private byte[] absentPayloadJson(
      UUID schoolId, UUID recordId, UUID studentId, UUID classId, Instant markedAt, String traceId)
      throws Exception {
    Map<String, Object> payload = new HashMap<>();
    payload.put("schoolId", schoolId.toString());
    payload.put("recordId", recordId.toString());
    payload.put("studentId", studentId.toString());
    payload.put("classId", classId.toString());
    payload.put("date", LocalDate.of(2026, 5, 31).toString());
    payload.put("status", "ABSENT");
    payload.put("previousStatus", null);
    payload.put("markedAt", markedAt.toString());
    payload.put("traceId", traceId);
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

  private UUID seedAbsentRecord(UUID schoolId, UUID studentId, UUID classId, UUID teacherId) {
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
                        Instant.now().minusSeconds(5)))
                .getId());
  }

  private UUID seedLinkedParent(UUID schoolId, UUID studentId, String phone) {
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
