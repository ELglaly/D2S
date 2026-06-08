package com.schoolbridge.api.homework;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Cross-tenant invisibility suite for {@link HomeworkItemRepository}. */
@SpringBootTest
class HomeworkItemRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired HomeworkItemRepository homeworkItemRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;
  private UUID homeworkInA;
  private UUID homeworkInB;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> homeworkItemRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");

    UUID teacherA = persistTeacher(schoolA, "teacher-a@hw-iso.test");
    UUID teacherB = persistTeacher(schoolB, "teacher-b@hw-iso.test");

    UUID classA =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolA, "Class A", "Grade 1", "2025-2026", null))
                    .getId());
    UUID classB =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolB, "Class B", "Grade 1", "2025-2026", null))
                    .getId());

    homeworkInA =
        tx.execute(
            s ->
                homeworkItemRepository
                    .save(
                        new HomeworkItem(
                            schoolA,
                            classA,
                            teacherA,
                            "Math",
                            "Page 10",
                            null,
                            LocalDate.now().plusDays(1),
                            false,
                            HomeworkStatus.PUBLISHED))
                    .getId());
    homeworkInB =
        tx.execute(
            s ->
                homeworkItemRepository
                    .save(
                        new HomeworkItem(
                            schoolB,
                            classB,
                            teacherB,
                            "Science",
                            "Chapter 3",
                            null,
                            LocalDate.now().plusDays(1),
                            false,
                            HomeworkStatus.PUBLISHED))
                    .getId());
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findById_underTenantA_cannotSeeHomeworkInB() {
    TenantContext.set(schoolA);
    var own = tx.execute(s -> homeworkItemRepository.findById(homeworkInA));
    var other = tx.execute(s -> homeworkItemRepository.findById(homeworkInB));
    assertThat(own).isPresent();
    assertThat(other).as("school A must not see school B's homework").isEmpty();
  }

  @Test
  void findFiltered_isFilteredByTenant() {
    TenantContext.set(schoolA);
    var page =
        tx.execute(
            s ->
                homeworkItemRepository.findFiltered(
                    null,
                    HomeworkStatus.PUBLISHED,
                    null,
                    null,
                    org.springframework.data.domain.Pageable.unpaged()));
    assertThat(page.getTotalElements())
        .as("school A should see only its own homework")
        .isEqualTo(1);
    assertThat(page.getContent().get(0).getId())
        .as("the visible item must be the one in school A")
        .isEqualTo(homeworkInA);
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
}
