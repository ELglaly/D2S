package com.schoolbridge.api.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.RlsTestRole;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the changelog-017 Row-Level Security policies and the {@link TenantSessionBinder} plumbing
 * that feeds them.
 *
 * <p><b>Why the role switch.</b> The migration leaves RLS un-FORCEd, so the owner bypasses it — and
 * Testcontainers connects as the bootstrap superuser, which bypasses RLS whether or not the table
 * is FORCEd. Asserting isolation on that connection measures nothing, so each case assumes {@link
 * RlsTestRole} first; see that class for the full reasoning.
 *
 * <p>Most cases query through {@link JdbcTemplate} rather than a repository on purpose: going
 * around Hibernate entirely is the only way to show the <em>database</em> is refusing,
 * independently of the {@code tenantFilter} that normally does the refusing.
 */
@SpringBootTest
class TenantRlsIntegrationTest extends AbstractIntegrationTest {

  private static final String SHARED_PHONE_HASH = "rls-test-shared-phone-hash";
  private static final String ASSUME_APP_ROLE = RlsTestRole.ASSUME;

  @Autowired SchoolRepository schoolRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired UserRepository userRepository;
  @Autowired TenantSessionBinder sessionBinder;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;
  private UUID studentInA;

  @BeforeEach
  void setUp() {
    TenantContext.clear();
    RlsTestRole.ensureExists(jdbc);
    schoolA = persistSchool("RLS Alpha");
    schoolB = persistSchool("RLS Beta");
    studentInA = persistStudent(schoolA, "Amina Hassan");
    persistStudent(schoolB, "Bilal Nasser");
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void databaseHidesAnotherTenantsRowEvenWhenHibernateIsBypassed() {
    Long visible =
        tx.execute(
            status -> {
              jdbc.execute(ASSUME_APP_ROLE);
              sessionBinder.bindTenant(schoolB);
              return jdbc.queryForObject(
                  "select count(*) from students where id = ?", Long.class, studentInA);
            });

    assertThat(visible).as("school B must not see school A's student").isZero();
  }

  @Test
  void boundTenantStillSeesItsOwnRow() {
    Long visible =
        tx.execute(
            status -> {
              jdbc.execute(ASSUME_APP_ROLE);
              sessionBinder.bindTenant(schoolA);
              return jdbc.queryForObject(
                  "select count(*) from students where id = ?", Long.class, studentInA);
            });

    assertThat(visible).as("the policy must not block the tenant that owns the row").isOne();
  }

  @Test
  void unboundTenantSeesNothingRatherThanEverything() {
    Long visible =
        tx.execute(
            status -> {
              jdbc.execute(ASSUME_APP_ROLE);
              return jdbc.queryForObject("select count(*) from students", Long.class);
            });

    assertThat(visible)
        .as(
            "an unbound tenant GUC must fail closed — current_setting(...) is NULL, so no row"
                + " satisfies the policy")
        .isZero();
  }

  @Test
  void withCheckRejectsAnInsertCarryingAnotherTenantsSchoolId() {
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      jdbc.execute(ASSUME_APP_ROLE);
                      TenantContext.runAs(
                          schoolA,
                          () ->
                              studentRepository.saveAndFlush(
                                  new Student(
                                      schoolB, "Smuggled", LocalDate.of(2015, 1, 1), null)));
                    }))
        .as("WITH CHECK must reject a row whose school_id differs from the bound tenant")
        .hasMessageContaining("row-level security");
  }

  @Test
  void parentLookupSeesNothingWithoutTheBypassAndBothSchoolsWithIt() {
    persistParent(schoolA, "Parent In A");
    persistParent(schoolB, "Parent In B");

    List<User> withoutBypass =
        tx.execute(
            status -> {
              jdbc.execute(ASSUME_APP_ROLE);
              return userRepository.findAllByPhoneHash(SHARED_PHONE_HASH);
            });

    List<User> withBypass =
        tx.execute(
            status -> {
              jdbc.execute(ASSUME_APP_ROLE);
              return sessionBinder.withBypass(
                  () -> userRepository.findAllByPhoneHash(SHARED_PHONE_HASH));
            });

    assertThat(withoutBypass)
        .as("this is why the bypass exists — otherwise the parent OTP flow resolves nobody")
        .isEmpty();
    assertThat(withBypass)
        .as("the lookup runs before any tenant is known and must still see both schools")
        .hasSize(2);
  }

  @Test
  void bypassIsReleasedSoTheRestOfTheTransactionStaysScoped() {
    Long visibleAfterBypass =
        tx.execute(
            status -> {
              jdbc.execute(ASSUME_APP_ROLE);
              sessionBinder.bindTenant(schoolB);
              sessionBinder.withBypass(() -> jdbc.queryForObject("select 1", Integer.class));
              return jdbc.queryForObject(
                  "select count(*) from students where id = ?", Long.class, studentInA);
            });

    assertThat(visibleAfterBypass)
        .as("withBypass must restore the predicate in its finally block")
        .isZero();
  }

  private UUID persistSchool(String name) {
    return tx.execute(
        status ->
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

  private UUID persistStudent(UUID schoolId, String fullName) {
    return tx.execute(
        status ->
            TenantContext.runAs(
                schoolId,
                () ->
                    studentRepository
                        .save(new Student(schoolId, fullName, LocalDate.of(2014, 5, 20), null))
                        .getId()));
  }

  private void persistParent(UUID schoolId, String name) {
    tx.executeWithoutResult(
        status ->
            TenantContext.runAs(
                schoolId,
                () ->
                    userRepository.save(
                        User.parent(schoolId, name, "+201000000000", SHARED_PHONE_HASH))));
  }
}
