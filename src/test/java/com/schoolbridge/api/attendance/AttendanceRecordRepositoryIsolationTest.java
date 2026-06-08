package com.schoolbridge.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
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

/** Cross-tenant invisibility suite for {@link AttendanceRecordRepository}. */
@SpringBootTest
class AttendanceRecordRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired AttendanceRecordRepository attendanceRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;
  private UUID recordInA;
  private UUID recordInB;
  private UUID studentInA;
  private UUID studentInB;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> attendanceRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");

    UUID teacherA = persistTeacher(schoolA, "ta@isolation.test");
    UUID teacherB = persistTeacher(schoolB, "tb@isolation.test");
    studentInA = persistStudent(schoolA, "Student A");
    studentInB = persistStudent(schoolB, "Student B");
    UUID classA = persistClass(schoolA, "3A", "Grade 3");
    UUID classB = persistClass(schoolB, "4B", "Grade 4");

    LocalDate today = LocalDate.of(2026, 5, 31);
    recordInA =
        tx.execute(
            s ->
                attendanceRepository
                    .save(
                        new AttendanceRecord(
                            schoolA,
                            studentInA,
                            classA,
                            today,
                            AttendanceStatus.ABSENT,
                            teacherA,
                            Instant.now()))
                    .getId());
    recordInB =
        tx.execute(
            s ->
                attendanceRepository
                    .save(
                        new AttendanceRecord(
                            schoolB,
                            studentInB,
                            classB,
                            today,
                            AttendanceStatus.PRESENT,
                            teacherB,
                            Instant.now()))
                    .getId());
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findById_underTenantA_cannotSeeRecordInB() {
    TenantContext.set(schoolA);
    var own = tx.execute(s -> attendanceRepository.findById(recordInA));
    var other = tx.execute(s -> attendanceRepository.findById(recordInB));
    assertThat(own).isPresent();
    assertThat(other).as("school A must not see school B's record").isEmpty();
  }

  @Test
  void findAll_underTenantB_returnsOnlyOwnRecords() {
    TenantContext.set(schoolB);
    var all = tx.execute(s -> attendanceRepository.findAll());
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getId()).isEqualTo(recordInB);
  }

  @Test
  void findByStudentIdAndClassIdAndDate_isolatedToTenant() {
    TenantContext.set(schoolA);
    var foreignStudentLookup =
        tx.execute(
            s ->
                attendanceRepository.findByStudentIdAndClassIdAndDate(
                    studentInB, UUID.randomUUID(), LocalDate.of(2026, 5, 31)));
    assertThat(foreignStudentLookup).isEmpty();
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

  private UUID persistTeacher(UUID schoolId, String email) {
    return tx.execute(
        s ->
            userRepository
                .save(
                    User.staff(
                        schoolId,
                        UserRole.TEACHER,
                        "Teacher " + email,
                        email,
                        passwordEncoder.encode("pass")))
                .getId());
  }

  private UUID persistStudent(UUID schoolId, String name) {
    return tx.execute(
        s ->
            studentRepository
                .save(new Student(schoolId, name, LocalDate.of(2015, 1, 1), null))
                .getId());
  }

  private UUID persistClass(UUID schoolId, String name, String grade) {
    return tx.execute(
        s ->
            classRepository
                .save(new SchoolClass(schoolId, name, grade, "2025-2026", null))
                .getId());
  }
}
