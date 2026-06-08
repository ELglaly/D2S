package com.schoolbridge.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Cross-tenant invisibility suite for {@link AttendanceAlertRecipientRepository}. */
@SpringBootTest
class AttendanceAlertRecipientRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired AttendanceAlertRecipientRepository recipientRepository;
  @Autowired AttendanceRecordRepository recordRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;
  private UUID recipientInA;
  private UUID recipientInB;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> recordRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");
    recipientInA = persistRecipient(schoolA, "+201090000010", "ta@alerts.test");
    recipientInB = persistRecipient(schoolB, "+201090000020", "tb@alerts.test");
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findById_underTenantA_cannotSeeRecipientInB() {
    TenantContext.set(schoolA);
    var own = tx.execute(s -> recipientRepository.findById(recipientInA));
    var other = tx.execute(s -> recipientRepository.findById(recipientInB));
    assertThat(own).isPresent();
    assertThat(other).as("school A must not see school B's alert recipient").isEmpty();
  }

  @Test
  void findAll_underTenantB_returnsOnlyOwnRecipients() {
    TenantContext.set(schoolB);
    var all = tx.execute(s -> recipientRepository.findAll());
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getId()).isEqualTo(recipientInB);
  }

  private UUID persistRecipient(UUID schoolId, String phone, String teacherEmail) {
    UUID teacherId =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.staff(
                            schoolId,
                            UserRole.TEACHER,
                            "Teacher",
                            teacherEmail,
                            passwordEncoder.encode("pass")))
                    .getId());
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
                    .save(new Student(schoolId, "Student " + phone, LocalDate.of(2015, 1, 1), null))
                    .getId());
    UUID classId =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolId, "Class", "Grade 3", "2025-2026", null))
                    .getId());
    UUID recordId =
        tx.execute(
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
    return tx.execute(
        s ->
            recipientRepository
                .save(new AttendanceAlertRecipient(schoolId, recordId, parentId, studentId))
                .getId());
  }

  private UUID persistSchool(String name) {
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
}
