package com.schoolbridge.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.common.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mandatory cross-tenant invisibility suite for {@link User}. Inserts two schools with one user
 * each, then verifies that under each tenant's {@link TenantContext} the other school's user is
 * invisible across every read path the repo exposes. This is the canonical template every future
 * {@code TenantEntity} repository must copy.
 */
@SpringBootTest
@Sql(
    scripts = {
      "classpath:sql/cleanup/all-data.sql",
      "classpath:sql/fixtures/common/schools.sql",
      "classpath:sql/fixtures/identity/isolation.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
    scripts = "classpath:sql/cleanup/all-data.sql",
    executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class UserRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired UserRepository userRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired TransactionTemplate tx;
  private static final UUID SCHOOL_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID SCHOOL_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID USER_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
  private static final UUID USER_B = UUID.fromString("30000000-0000-0000-0000-000000000002");

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findById_underTenantA_cannotSeeUserInB() {
    TenantContext.set(SCHOOL_A);
    var foundOwn = tx.execute(s -> userRepository.findById(USER_A));
    var foundOther = tx.execute(s -> userRepository.findById(USER_B));
    assertThat(foundOwn).isPresent();
    assertThat(foundOther).as("school A must not see school B's user").isEmpty();
  }

  @Test
  void findAll_underTenantB_returnsOnlyOwnUsers() {
    TenantContext.set(SCHOOL_B);
    var ownUsers = tx.execute(s -> userRepository.findAll());
    assertThat(ownUsers).hasSize(1);
    assertThat(ownUsers).extracting(User::getId).contains(USER_B).doesNotContain(USER_A);
  }

  @Test
  void findByPhoneHash_underTenantA_cannotSeeBsParent() {
    TenantContext.set(SCHOOL_A);
    var foundOther =
        tx.execute(s -> userRepository.findByPhoneHash(blindIndex.hash("+201080000002")));
    assertThat(foundOther).as("school A must not resolve school B's phone").isEmpty();
  }

  @Test
  void unauthenticatedLookup_seesEverySchool() {
    // Parent OTP request runs without a tenant — the aspect must NOT enable the filter, otherwise
    // a parent could never be located in the first place. findAllByPhoneHash returns matches
    // across schools.
    TenantContext.clear();
    var all = tx.execute(s -> userRepository.findAllByPhoneHash(blindIndex.hash("+201080000001")));
    assertThat(all).hasSize(1);
  }
}
