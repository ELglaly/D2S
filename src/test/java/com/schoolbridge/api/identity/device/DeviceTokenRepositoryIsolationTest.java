package com.schoolbridge.api.identity.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.common.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cross-tenant invisibility suite for {@link DeviceToken}. Canonical pattern from {@code
 * UserRepositoryIsolationTest}: two schools, one device each, each school must not see the other's
 * devices.
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
class DeviceTokenRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired DeviceTokenRepository deviceTokenRepository;
  @Autowired TransactionTemplate tx;
  private static final UUID SCHOOL_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID SCHOOL_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID DEVICE_A = UUID.fromString("31000000-0000-0000-0000-000000000001");
  private static final UUID DEVICE_B = UUID.fromString("31000000-0000-0000-0000-000000000002");

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findById_underTenantA_cannotSeeDeviceInB() {
    TenantContext.set(SCHOOL_A);
    var foundOwn = tx.execute(s -> deviceTokenRepository.findById(DEVICE_A));
    var foundOther = tx.execute(s -> deviceTokenRepository.findById(DEVICE_B));
    assertThat(foundOwn).isPresent();
    assertThat(foundOther).as("school A must not see school B's device token").isEmpty();
  }

  @Test
  void findAll_underTenantB_returnsOnlyOwnDevices() {
    TenantContext.set(SCHOOL_B);
    var own = tx.execute(s -> deviceTokenRepository.findAll());
    assertThat(own).hasSize(1);
    assertThat(own).extracting(DeviceToken::getId).contains(DEVICE_B).doesNotContain(DEVICE_A);
  }
}
