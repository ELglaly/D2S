package com.schoolbridge.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
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
 * Mandatory cross-tenant invisibility suite for {@link User}. Inserts two schools with one user
 * each, then verifies that under each tenant's {@link TenantContext} the other school's user is
 * invisible across every read path the repo exposes. This is the canonical template every future
 * {@code TenantEntity} repository must copy.
 */
@SpringBootTest
class UserRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;
  private UUID userInA;
  private UUID userInB;

  @BeforeEach
  void setUp() {
    // Clean slate so the test is deterministic across the singleton-container test run.
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");

    userInA =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.parent(
                            schoolA, "Parent A", "+201000000001", blindIndex.hash("+201000000001")))
                    .getId());
    userInB =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.parent(
                            schoolB, "Parent B", "+201000000002", blindIndex.hash("+201000000002")))
                    .getId());
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findById_underTenantA_cannotSeeUserInB() {
    TenantContext.set(schoolA);
    var foundOwn = tx.execute(s -> userRepository.findById(userInA));
    var foundOther = tx.execute(s -> userRepository.findById(userInB));
    assertThat(foundOwn).isPresent();
    assertThat(foundOther).as("school A must not see school B's user").isEmpty();
  }

  @Test
  void findAll_underTenantB_returnsOnlyOwnUsers() {
    TenantContext.set(schoolB);
    var ownUsers = tx.execute(s -> userRepository.findAll());
    assertThat(ownUsers).hasSize(1);
    assertThat(ownUsers.get(0).getId()).isEqualTo(userInB);
  }

  @Test
  void findByPhoneHash_underTenantA_cannotSeeBsParent() {
    TenantContext.set(schoolA);
    var foundOther =
        tx.execute(s -> userRepository.findByPhoneHash(blindIndex.hash("+201000000002")));
    assertThat(foundOther).as("school A must not resolve school B's phone").isEmpty();
  }

  @Test
  void unauthenticatedLookup_seesEverySchool() {
    // Parent OTP request runs without a tenant — the aspect must NOT enable the filter, otherwise
    // a parent could never be located in the first place. findAllByPhoneHash returns matches
    // across schools.
    TenantContext.clear();
    var all = tx.execute(s -> userRepository.findAllByPhoneHash(blindIndex.hash("+201000000001")));
    assertThat(all).hasSize(1);
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
