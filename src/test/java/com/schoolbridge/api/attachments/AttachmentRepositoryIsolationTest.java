package com.schoolbridge.api.attachments;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.RlsTestRole;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cross-tenant invisibility for {@link AttachmentRepository}, at both layers.
 *
 * <p>The first two cases cover the Hibernate {@code tenantFilter}, including the {@code findById}
 * override that exists because {@code @Filter} never applies to {@code EntityManager.find()}. The
 * last covers the changelog-018 RLS policy directly, under an unprivileged role — Testcontainers
 * connects as the bootstrap superuser, which bypasses RLS unconditionally, so without {@code SET
 * LOCAL ROLE} that assertion would pass with the policy deleted.
 */
@SpringBootTest
class AttachmentRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired AttachmentRepository attachmentRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  private UUID schoolA;
  private UUID schoolB;
  private UUID attachmentInA;
  private UUID attachmentInB;

  @BeforeEach
  void setUp() {
    TenantContext.clear();
    RlsTestRole.ensureExists(jdbc);
    tx.executeWithoutResult(s -> attachmentRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");
    attachmentInA = persistAttachment(schoolA, persistStaff(schoolA, "a@attach-iso.test"));
    attachmentInB = persistAttachment(schoolB, persistStaff(schoolB, "b@attach-iso.test"));
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findByIdUnderTenantACannotSeeAttachmentInB() {
    TenantContext.set(schoolA);
    var own = tx.execute(s -> attachmentRepository.findById(attachmentInA));
    var other = tx.execute(s -> attachmentRepository.findById(attachmentInB));
    assertThat(own).isPresent();
    assertThat(other).as("school A must not see school B's attachment").isEmpty();
  }

  @Test
  void countIsFilteredByTenant() {
    TenantContext.set(schoolA);
    long visible = tx.execute(s -> attachmentRepository.count());
    assertThat(visible).isEqualTo(1);
  }

  @Test
  void databaseHidesAnotherTenantsRowsFromARawQuery() {
    // Raw SQL, going around the Hibernate filter entirely, so only the RLS policy can be doing the
    // work. The table really holds both rows; each tenant must see exactly its own. Asserting the
    // unfiltered total as well is what stops this passing vacuously — a missing grant would make
    // every scoped count zero and the isolation claim meaningless.
    Long total = jdbc.queryForObject("select count(*) from attachments", Long.class);
    Long fromA = countAttachmentsAs(schoolA);
    Long fromB = countAttachmentsAs(schoolB);

    assertThat(total).as("both rows exist for the owner").isEqualTo(2L);
    assertThat(fromA).as("school A sees only its own row").isEqualTo(1L);
    assertThat(fromB).as("school B sees only its own row").isEqualTo(1L);
    assertThat(idsVisibleAs(schoolA)).containsExactly(attachmentInA);
    assertThat(idsVisibleAs(schoolB)).containsExactly(attachmentInB);
  }

  @Test
  void unboundTenantSeesNothingRatherThanEveryTenantsRows() {
    Long visible =
        tx.execute(
            s -> {
              jdbc.execute(RlsTestRole.ASSUME);
              return jdbc.queryForObject("select count(*) from attachments", Long.class);
            });
    assertThat(visible).as("an unbound tenant must fail closed, not open").isEqualTo(0L);
  }

  private java.util.List<UUID> idsVisibleAs(UUID schoolId) {
    return tx.execute(
        s -> {
          jdbc.execute(RlsTestRole.ASSUME);
          jdbc.queryForObject(
              "select set_config('app.current_tenant', ?, true)",
              String.class,
              schoolId.toString());
          return jdbc.queryForList("select id from attachments", UUID.class);
        });
  }

  private Long countAttachmentsAs(UUID schoolId) {
    return tx.execute(
        s -> {
          jdbc.execute(RlsTestRole.ASSUME);
          jdbc.queryForObject(
              "select set_config('app.current_tenant', ?, true)",
              String.class,
              schoolId.toString());
          return jdbc.queryForObject("select count(*) from attachments", Long.class);
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

  private UUID persistAttachment(UUID schoolId, UUID uploaderId) {
    return tx.execute(
        s -> {
          Attachment attachment =
              new Attachment(
                  schoolId,
                  uploaderId,
                  AttachmentKeys.forAttachment(schoolId, UUID.randomUUID(), Instant.now()),
                  "photo.png",
                  "image/png",
                  512L);
          attachment.markUploaded(512L);
          attachment.markClean("image/png", AvResult.SKIPPED, Instant.now());
          return attachmentRepository.save(attachment).getId();
        });
  }
}
