package com.schoolbridge.api.homework;

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
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Cross-tenant invisibility suite for {@link HomeworkRecipientRepository}. */
@SpringBootTest
class HomeworkRecipientRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired HomeworkItemRepository homeworkItemRepository;
  @Autowired HomeworkRecipientRepository recipientRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;
  private UUID homeworkInA;
  private UUID homeworkInB;
  private UUID recipientInA;
  private UUID recipientInB;
  private UUID parentInA;
  private UUID parentInB;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> homeworkItemRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");

    UUID teacherA = persistTeacher(schoolA, "teacher-a@hr-iso.test");
    UUID teacherB = persistTeacher(schoolB, "teacher-b@hr-iso.test");

    parentInA = persistParent(schoolA, "+201230000001");
    parentInB = persistParent(schoolB, "+201230000002");

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

    UUID studentA =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolA, "Child A", LocalDate.of(2015, 1, 1), null))
                    .getId());
    UUID studentB =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolB, "Child B", LocalDate.of(2015, 1, 1), null))
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

    recipientInA =
        tx.execute(
            s ->
                recipientRepository
                    .save(new HomeworkRecipient(schoolA, homeworkInA, parentInA, studentA))
                    .getId());
    recipientInB =
        tx.execute(
            s ->
                recipientRepository
                    .save(new HomeworkRecipient(schoolB, homeworkInB, parentInB, studentB))
                    .getId());
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
    assertThat(other).as("school A must not see school B's recipient").isEmpty();
  }

  @Test
  void findFeedForParentAndStudent_isFilteredByTenant() {
    TenantContext.set(schoolA);
    // Query using schoolB's parent — must return zero results under schoolA's tenant context.
    var feed =
        tx.execute(
            s ->
                recipientRepository.findFeedForParentAndStudent(
                    parentInB, UUID.randomUUID(), Pageable.unpaged()));
    assertThat(feed.getTotalElements()).as("school A must not see school B's parent feed").isZero();
  }

  @Test
  void findAllByHomeworkId_isFilteredByTenant() {
    TenantContext.set(schoolA);
    var list = tx.execute(s -> recipientRepository.findAllByHomeworkId(homeworkInB));
    assertThat(list).as("school A querying school B's homework must see zero recipients").isEmpty();
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

  private UUID persistParent(UUID schoolId, String phone) {
    return tx.execute(
        s ->
            userRepository
                .save(User.parent(schoolId, "Parent " + phone, phone, blindIndex.hash(phone)))
                .getId());
  }
}
