package com.schoolbridge.api.classes;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.entity.Enrollment;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/** Cross-tenant invisibility suite for {@link EnrollmentRepository}. */
@SpringBootTest
class EnrollmentRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired EnrollmentRepository enrollmentRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired com.schoolbridge.api.identity.UserRepository userRepository;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;
  private UUID enrollmentInA;
  private UUID enrollmentInB;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> enrollmentRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");

    UUID studentA =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolA, "Student A", LocalDate.of(2015, 1, 1), null))
                    .getId());
    UUID classA =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolA, "3A", "Grade 3", "2025-2026", null))
                    .getId());
    enrollmentInA =
        tx.execute(
            s -> enrollmentRepository.save(new Enrollment(schoolA, studentA, classA)).getId());

    UUID studentB =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolB, "Student B", LocalDate.of(2015, 1, 1), null))
                    .getId());
    UUID classB =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolB, "4B", "Grade 4", "2025-2026", null))
                    .getId());
    enrollmentInB =
        tx.execute(
            s -> enrollmentRepository.save(new Enrollment(schoolB, studentB, classB)).getId());
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findById_underTenantA_cannotSeeEnrollmentInB() {
    TenantContext.set(schoolA);
    var own = tx.execute(s -> enrollmentRepository.findById(enrollmentInA));
    var other = tx.execute(s -> enrollmentRepository.findById(enrollmentInB));
    assertThat(own).isPresent();
    assertThat(other).as("school A must not see school B's enrollment").isEmpty();
  }

  @Test
  void findAll_underTenantB_returnsOnlyOwnEnrollments() {
    TenantContext.set(schoolB);
    var all = tx.execute(s -> enrollmentRepository.findAll());
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getId()).isEqualTo(enrollmentInB);
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
