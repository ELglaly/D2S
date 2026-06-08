package com.schoolbridge.api.identity.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
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
 * Cross-tenant invisibility suite for {@link DeviceToken}. Canonical pattern from {@code
 * UserRepositoryIsolationTest}: two schools, one device each, each school must not see the other's
 * devices.
 */
@SpringBootTest
class DeviceTokenRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired DeviceTokenRepository deviceTokenRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;
  private UUID deviceInA;
  private UUID deviceInB;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> deviceTokenRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = persistSchool("DeviceIsolation Alpha");
    schoolB = persistSchool("DeviceIsolation Beta");

    UUID userInA = persistUser(schoolA, "a@device.test");
    UUID userInB = persistUser(schoolB, "b@device.test");

    deviceInA =
        tx.execute(
            s ->
                deviceTokenRepository
                    .save(
                        new DeviceToken(
                            schoolA, userInA, DevicePlatform.ANDROID, "fcm-token-a", "device-a"))
                    .getId());
    deviceInB =
        tx.execute(
            s ->
                deviceTokenRepository
                    .save(
                        new DeviceToken(
                            schoolB, userInB, DevicePlatform.IOS, "fcm-token-b", "device-b"))
                    .getId());
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findById_underTenantA_cannotSeeDeviceInB() {
    TenantContext.set(schoolA);
    var foundOwn = tx.execute(s -> deviceTokenRepository.findById(deviceInA));
    var foundOther = tx.execute(s -> deviceTokenRepository.findById(deviceInB));
    assertThat(foundOwn).isPresent();
    assertThat(foundOther).as("school A must not see school B's device token").isEmpty();
  }

  @Test
  void findAll_underTenantB_returnsOnlyOwnDevices() {
    TenantContext.set(schoolB);
    var own = tx.execute(s -> deviceTokenRepository.findAll());
    assertThat(own).hasSize(1);
    assertThat(own.get(0).getId()).isEqualTo(deviceInB);
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

  private UUID persistUser(UUID schoolId, String email) {
    return tx.execute(
        s ->
            userRepository
                .save(
                    User.staff(
                        schoolId,
                        com.schoolbridge.api.identity.UserRole.SCHOOL_ADMIN,
                        "Test User",
                        email,
                        "hash"))
                .getId());
  }
}
