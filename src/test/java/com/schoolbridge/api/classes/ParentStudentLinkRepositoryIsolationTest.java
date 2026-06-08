package com.schoolbridge.api.classes;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
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

/** Cross-tenant invisibility suite for {@link ParentStudentLinkRepository}. */
@SpringBootTest
class ParentStudentLinkRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired ParentStudentLinkRepository linkRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;
  private UUID linkInA;
  private UUID linkInB;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> linkRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");

    UUID parentA =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.parent(
                            schoolA, "Parent A", "+201000000011", blindIndex.hash("+201000000011")))
                    .getId());
    UUID studentA =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolA, "Child A", LocalDate.of(2015, 1, 1), null))
                    .getId());
    linkInA =
        tx.execute(
            s ->
                linkRepository
                    .save(
                        new ParentStudentLink(
                            schoolA, parentA, studentA, RelationshipType.MOTHER, true))
                    .getId());

    UUID parentB =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.parent(
                            schoolB, "Parent B", "+201000000012", blindIndex.hash("+201000000012")))
                    .getId());
    UUID studentB =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolB, "Child B", LocalDate.of(2015, 1, 1), null))
                    .getId());
    linkInB =
        tx.execute(
            s ->
                linkRepository
                    .save(
                        new ParentStudentLink(
                            schoolB, parentB, studentB, RelationshipType.FATHER, true))
                    .getId());
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findById_underTenantA_cannotSeeLinkInB() {
    TenantContext.set(schoolA);
    var own = tx.execute(s -> linkRepository.findById(linkInA));
    var other = tx.execute(s -> linkRepository.findById(linkInB));
    assertThat(own).isPresent();
    assertThat(other).as("school A must not see school B's parent link").isEmpty();
  }

  @Test
  void findAll_underTenantB_returnsOnlyOwnLinks() {
    TenantContext.set(schoolB);
    var all = tx.execute(s -> linkRepository.findAll());
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getId()).isEqualTo(linkInB);
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
