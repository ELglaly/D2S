package com.schoolbridge.api.classes;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.entity.Student;
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

/** Cross-tenant invisibility suite for {@link StudentRepository}. */
@SpringBootTest
class StudentRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired StudentRepository studentRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired com.schoolbridge.api.identity.UserRepository userRepository;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;
  private UUID studentInA;
  private UUID studentInB;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");

    studentInA =
        tx.execute(
            s ->
                studentRepository
                    .save(
                        new Student(schoolA, "Ahmed Mohamed", LocalDate.of(2015, 3, 15), "EXT-001"))
                    .getId());
    studentInB =
        tx.execute(
            s ->
                studentRepository
                    .save(
                        new Student(schoolB, "Fatima Hassan", LocalDate.of(2014, 7, 22), "EXT-002"))
                    .getId());
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findById_underTenantA_cannotSeeStudentInB() {
    TenantContext.set(schoolA);
    var own = tx.execute(s -> studentRepository.findById(studentInA));
    var other = tx.execute(s -> studentRepository.findById(studentInB));
    assertThat(own).isPresent();
    assertThat(other).as("school A must not see school B's student").isEmpty();
  }

  @Test
  void findAll_underTenantB_returnsOnlyOwnStudents() {
    TenantContext.set(schoolB);
    var all = tx.execute(s -> studentRepository.findAll());
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getId()).isEqualTo(studentInB);
  }

  @Test
  void findByExternalId_underTenantA_cannotSeeStudentInB() {
    TenantContext.set(schoolA);
    var other = tx.execute(s -> studentRepository.findByExternalId("EXT-002"));
    assertThat(other).as("school A must not resolve school B's external id").isEmpty();
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
