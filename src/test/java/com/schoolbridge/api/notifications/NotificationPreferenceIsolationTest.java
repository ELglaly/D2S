package com.schoolbridge.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.RlsTestRole;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.integrations.NotificationChannel;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cross-tenant invisibility for both notification tables, at both layers.
 *
 * <p>The Hibernate cases cover the {@code tenantFilter} including the {@code findById} overrides
 * that exist because {@code @Filter} never applies to {@code EntityManager.find()}. The raw-SQL
 * cases cover the changelog-019 RLS policies under an unprivileged role — Testcontainers connects
 * as the bootstrap superuser, which bypasses RLS unconditionally, so without {@code SET LOCAL ROLE}
 * those assertions would still pass with the policies deleted.
 *
 * <p>These rows carry a consent decision. A leak across schools here is not a data-exposure bug in
 * the abstract: it is one school's administrator seeing, or overwriting, another school's parents'
 * choices about being contacted.
 */
@SpringBootTest
@Sql(
    scripts = {
      "classpath:sql/cleanup/all-data.sql",
      "classpath:sql/fixtures/common/schools.sql",
      "classpath:sql/fixtures/identity/isolation.sql",
      "classpath:sql/fixtures/identity/notification-isolation.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
    scripts = "classpath:sql/cleanup/all-data.sql",
    executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class NotificationPreferenceIsolationTest extends AbstractIntegrationTest {

  @Autowired NotificationPreferenceRepository preferenceRepository;
  @Autowired NotificationSettingsRepository settingsRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  private static final UUID SCHOOL_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID SCHOOL_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID PREFERENCE_A = UUID.fromString("33000000-0000-0000-0000-000000000001");
  private static final UUID PREFERENCE_B = UUID.fromString("33000000-0000-0000-0000-000000000002");
  private static final UUID SETTINGS_A = UUID.fromString("34000000-0000-0000-0000-000000000001");

  @BeforeEach
  void setUp() {
    TenantContext.clear();
    RlsTestRole.ensureExists(jdbc);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findByIdUnderTenantACannotSeePreferenceInB() {
    TenantContext.set(SCHOOL_A);
    var own = tx.execute(s -> preferenceRepository.findById(PREFERENCE_A));
    var other = tx.execute(s -> preferenceRepository.findById(PREFERENCE_B));
    assertThat(own).isPresent();
    assertThat(other).as("school A must not see school B's preference row").isEmpty();
  }

  @Test
  void settingsFindByIdIsAlsoTenantScoped() {
    TenantContext.set(SCHOOL_B);
    var other = tx.execute(s -> settingsRepository.findById(SETTINGS_A));
    assertThat(other).as("school B must not see school A's quiet-hours row").isEmpty();
  }

  @Test
  void countsAreFilteredByTenant() {
    TenantContext.set(SCHOOL_A);
    long preferences = tx.execute(s -> preferenceRepository.count());
    long settings = tx.execute(s -> settingsRepository.count());
    assertThat(preferences).isEqualTo(1);
    assertThat(settings).isEqualTo(1);
  }

  @Test
  void databaseHidesAnotherTenantsRowsFromARawQuery() {
    // Raw SQL, going around the Hibernate filter entirely, so only the RLS policy can be doing the
    // work. Asserting the unfiltered total as well is what stops this passing vacuously — a missing
    // grant would make every scoped count zero and the isolation claim meaningless.
    Long total = jdbc.queryForObject("select count(*) from notification_preferences", Long.class);
    assertThat(total).as("both rows exist for the owner").isEqualTo(2L);
    assertThat(countAs(SCHOOL_A, "notification_preferences")).isEqualTo(1L);
    assertThat(countAs(SCHOOL_B, "notification_preferences")).isEqualTo(1L);
    assertThat(countAs(SCHOOL_A, "notification_settings")).isEqualTo(1L);
    assertThat(idsVisibleAs(SCHOOL_A)).containsExactly(PREFERENCE_A);
    assertThat(idsVisibleAs(SCHOOL_B)).containsExactly(PREFERENCE_B);
  }

  @Test
  void unboundTenantSeesNothingRatherThanEveryTenantsRows() {
    Long preferences =
        tx.execute(
            s -> {
              jdbc.execute(RlsTestRole.ASSUME);
              return jdbc.queryForObject(
                  "select count(*) from notification_preferences", Long.class);
            });
    Long settings =
        tx.execute(
            s -> {
              jdbc.execute(RlsTestRole.ASSUME);
              return jdbc.queryForObject("select count(*) from notification_settings", Long.class);
            });
    assertThat(preferences).as("an unbound tenant must fail closed, not open").isEqualTo(0L);
    assertThat(settings).as("an unbound tenant must fail closed, not open").isEqualTo(0L);
  }

  private List<UUID> idsVisibleAs(UUID schoolId) {
    return tx.execute(
        s -> {
          jdbc.execute(RlsTestRole.ASSUME);
          jdbc.queryForObject(
              "select set_config('app.current_tenant', ?, true)",
              String.class,
              schoolId.toString());
          return jdbc.queryForList("select id from notification_preferences", UUID.class);
        });
  }

  private Long countAs(UUID schoolId, String table) {
    return tx.execute(
        s -> {
          jdbc.execute(RlsTestRole.ASSUME);
          jdbc.queryForObject(
              "select set_config('app.current_tenant', ?, true)",
              String.class,
              schoolId.toString());
          return jdbc.queryForObject("select count(*) from " + table, Long.class);
        });
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

  private UUID persistStaff(UUID schoolId, String email) {
    return tx.execute(
        s ->
            userRepository
                .save(
                    User.staff(
                        schoolId,
                        UserRole.SCHOOL_ADMIN,
                        "Admin " + email,
                        email,
                        passwordEncoder.encode("pass")))
                .getId());
  }

  private UUID persistPreference(UUID schoolId, UUID userId) {
    return tx.execute(
        s ->
            preferenceRepository
                .save(
                    new NotificationPreference(
                        schoolId,
                        userId,
                        NotificationCategory.HOMEWORK,
                        true,
                        List.of(NotificationChannel.PUSH, NotificationChannel.SMS)))
                .getId());
  }

  private UUID persistSettings(UUID schoolId, UUID userId) {
    return tx.execute(
        s ->
            settingsRepository
                .save(
                    new NotificationSettings(
                        schoolId, userId, true, LocalTime.of(22, 0), LocalTime.of(6, 0)))
                .getId());
  }
}
