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
import com.schoolbridge.api.common.outbox.OutboxEvent;
import com.schoolbridge.api.common.outbox.OutboxEventRecorder;
import com.schoolbridge.api.common.outbox.OutboxRepository;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives both responsibilities of {@link AttendanceSweeper} directly — deferred-release and
 * missed-roster detection — bypassing {@code @Scheduled} timing. The sweeper bean is gated off in
 * the default test profile so we construct it ourselves with autowired collaborators.
 */
@SpringBootTest
class AttendanceSweeperTest extends AbstractIntegrationTest {

  @Autowired AttendanceAlertService alertService;
  @Autowired AttendanceRecordRepository recordRepository;
  @Autowired AttendanceAlertRecipientRepository recipientRepository;
  @Autowired ParentStudentLinkRepository linkRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired OutboxRepository outboxRepository;
  @Autowired OutboxEventRecorder outboxRecorder;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired FakeWhatsAppClient fakeWhatsApp;
  @Autowired StringRedisTemplate redis;
  @Autowired MeterRegistry meterRegistry;
  @Autowired TransactionTemplate tx;

  private AttendanceSweeper sweeper;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> recordRepository.deleteAll());
    tx.executeWithoutResult(s -> linkRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> outboxRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());
    fakeWhatsApp.reset();
    purgeRedis("attendance:roster_missed:*");
    sweeper =
        new AttendanceSweeper(
            recipientRepository,
            recordRepository,
            alertService,
            schoolRepository,
            classRepository,
            outboxRecorder,
            redis,
            meterRegistry,
            tx,
            Duration.ofHours(36));
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  // --- deferred-release ----------------------------------------------------

  @Test
  void releaseDeferredAlerts_dispatchesRowsWhoseHoldHasClosed() {
    UUID schoolId = seedSchool("Sweeper School A", SchoolSettings.defaults());
    UUID teacherId = seedTeacher(schoolId, "teacher-sw@a.test");
    UUID studentId = seedStudent(schoolId, "Karim");
    UUID classId = seedClass(schoolId, "3A");
    UUID recordId = seedAbsentRecord(schoolId, studentId, classId, teacherId);
    UUID parentId = seedLinkedParent(schoolId, studentId, "+201091000001");
    UUID recipientId =
        seedDeferredRecipient(
            schoolId, recordId, parentId, studentId, Instant.now().minusSeconds(5));

    sweeper.releaseDeferredAlerts();

    assertThat(fakeWhatsApp.sent()).hasSize(1);
    TenantContext.set(schoolId);
    AttendanceAlertRecipient updated =
        tx.execute(s -> recipientRepository.findById(recipientId).orElseThrow());
    AttendanceRecord stamped = tx.execute(s -> recordRepository.findById(recordId).orElseThrow());
    TenantContext.clear();
    assertThat(updated.getDeliveryStatus()).isEqualTo(AttendanceAlertStatus.SENT);
    assertThat(updated.getMessageId()).isNotNull();
    assertThat(stamped.getAlertSentAt()).isNotNull();
  }

  @Test
  void releaseDeferredAlerts_skipsRowsWhoseHoldIsStillFuture() {
    UUID schoolId = seedSchool("Sweeper School B", SchoolSettings.defaults());
    UUID teacherId = seedTeacher(schoolId, "teacher-sw@b.test");
    UUID studentId = seedStudent(schoolId, "Layla");
    UUID classId = seedClass(schoolId, "4B");
    UUID recordId = seedAbsentRecord(schoolId, studentId, classId, teacherId);
    UUID parentId = seedLinkedParent(schoolId, studentId, "+201091000002");
    UUID recipientId =
        seedDeferredRecipient(
            schoolId, recordId, parentId, studentId, Instant.now().plus(Duration.ofHours(2)));

    sweeper.releaseDeferredAlerts();

    assertThat(fakeWhatsApp.sent()).isEmpty();
    TenantContext.set(schoolId);
    AttendanceAlertRecipient untouched =
        tx.execute(s -> recipientRepository.findById(recipientId).orElseThrow());
    TenantContext.clear();
    assertThat(untouched.getDeliveryStatus()).isEqualTo(AttendanceAlertStatus.DEFERRED);
  }

  // --- missed-roster -------------------------------------------------------

  @Test
  void detectMissedRosters_emitsOneOutboxEventPerUnsubmittedClass() {
    SchoolSettings alwaysPastDueBy = settingsWithRosterDueBy(LocalTime.of(0, 0));
    UUID schoolId = seedSchool("Roster School A", alwaysPastDueBy);
    UUID classA = seedClass(schoolId, "Class A");
    UUID classB = seedClass(schoolId, "Class B");

    sweeper.detectMissedRosters();

    List<OutboxEvent> events = rosterMissedEvents();
    Set<UUID> aggregateIds =
        events.stream().map(OutboxEvent::getAggregateId).collect(Collectors.toSet());
    assertThat(events).hasSize(2);
    assertThat(aggregateIds).containsExactlyInAnyOrder(classA, classB);
  }

  @Test
  void detectMissedRosters_isIdempotent_redisDedup() {
    SchoolSettings alwaysPastDueBy = settingsWithRosterDueBy(LocalTime.of(0, 0));
    UUID schoolId = seedSchool("Roster School B", alwaysPastDueBy);
    seedClass(schoolId, "Only Class");

    sweeper.detectMissedRosters();
    sweeper.detectMissedRosters();

    assertThat(rosterMissedEvents())
        .as("second sweep within the dedup TTL must not re-emit")
        .hasSize(1);
  }

  @Test
  void detectMissedRosters_skipsClassesWithASubmittedRoster() {
    SchoolSettings alwaysPastDueBy = settingsWithRosterDueBy(LocalTime.of(0, 0));
    UUID schoolId = seedSchool("Roster School C", alwaysPastDueBy);
    UUID teacherId = seedTeacher(schoolId, "teacher-roster@c.test");
    UUID studentId = seedStudent(schoolId, "Mariam");
    UUID submittedClass = seedClass(schoolId, "Submitted");
    UUID emptyClass = seedClass(schoolId, "Empty");
    // Seed an attendance record for "Submitted" so the sweeper considers it covered.
    tx.executeWithoutResult(
        s ->
            recordRepository.save(
                new AttendanceRecord(
                    schoolId,
                    studentId,
                    submittedClass,
                    LocalDate.now(),
                    AttendanceStatus.PRESENT,
                    teacherId,
                    Instant.now())));

    sweeper.detectMissedRosters();

    List<OutboxEvent> events = rosterMissedEvents();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getAggregateId())
        .as("only the empty class should be flagged")
        .isEqualTo(emptyClass);
  }

  @Test
  void detectMissedRosters_doesNothingBeforeDueBy() {
    SchoolSettings neverPastDueBy = settingsWithRosterDueBy(LocalTime.of(23, 59));
    UUID schoolId = seedSchool("Roster School D", neverPastDueBy);
    seedClass(schoolId, "Class");

    sweeper.detectMissedRosters();

    assertThat(rosterMissedEvents())
        .as("now is virtually never past 23:59 in school-local time")
        .isEmpty();
  }

  // --- helpers -------------------------------------------------------------

  private SchoolSettings settingsWithRosterDueBy(LocalTime dueBy) {
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
        dueBy);
  }

  private List<OutboxEvent> rosterMissedEvents() {
    return outboxRepository.findAll().stream()
        .filter(e -> "attendance.roster_missed".equals(e.getEventType()))
        .toList();
  }

  private void purgeRedis(String pattern) {
    Set<String> keys = redis.keys(pattern);
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
  }

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
                        Instant.now()))
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

  private UUID seedDeferredRecipient(
      UUID schoolId, UUID recordId, UUID parentId, UUID studentId, Instant deferredUntil) {
    return tx.execute(
        s -> {
          AttendanceAlertRecipient row =
              new AttendanceAlertRecipient(schoolId, recordId, parentId, studentId);
          row.markDeferred(deferredUntil);
          return recipientRepository.save(row).getId();
        });
  }
}
