package com.schoolbridge.api.classes;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cross-tenant invisibility suite for {@link SchoolClassRepository}. Template: {@code
 * UserRepositoryIsolationTest}.
 */
@SpringBootTest
class SchoolClassRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired SchoolClassRepository classRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired com.schoolbridge.api.identity.UserRepository userRepository;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;
  private UUID classInA;
  private UUID classInB;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = persistSchool("AlphaSchool");
    schoolB = persistSchool("BetaSchool");

    classInA =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolA, "3A", "Grade 3", "2025-2026", null))
                    .getId());
    classInB =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolB, "4B", "Grade 4", "2025-2026", null))
                    .getId());
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findById_underTenantA_cannotSeeClassInB() {
    TenantContext.set(schoolA);
    var own = tx.execute(s -> classRepository.findById(classInA));
    var other = tx.execute(s -> classRepository.findById(classInB));
    assertThat(own).isPresent();
    assertThat(other).as("school A must not see school B's class").isEmpty();
  }

  @Test
  void findAll_underTenantB_returnsOnlyOwnClasses() {
    TenantContext.set(schoolB);
    var all = tx.execute(s -> classRepository.findAll());
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getId()).isEqualTo(classInB);
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
