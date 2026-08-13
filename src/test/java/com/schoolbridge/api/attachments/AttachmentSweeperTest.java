package com.schoolbridge.api.attachments;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.attachments.storage.ObjectStorage;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.time.Duration;
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
 * The sweeper is disabled in the test profile so ambient runs cannot race other suites' assertions;
 * this constructs it directly and invokes the scheduled method.
 *
 * <p>Ages are backdated with raw SQL because {@code created_at} is {@code @CreationTimestamp} and
 * {@code updatable = false} — there is no domain path to an old row, and there should not be one.
 */
@SpringBootTest
class AttachmentSweeperTest extends AbstractIntegrationTest {

  @Autowired AttachmentRepository attachmentRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired ObjectStorage storage;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  private UUID schoolId;
  private UUID uploaderId;

  @BeforeEach
  void setUp() {
    TenantContext.clear();
    tx.executeWithoutResult(s -> attachmentRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());
    schoolId = persistSchool();
    uploaderId = persistAdmin();
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void deletesUploadsAbandonedBeforeCompletion() {
    UUID abandoned = persistAttachment(AttachmentStatus.PENDING);
    UUID fresh = persistAttachment(AttachmentStatus.PENDING);
    backdate(abandoned, Duration.ofDays(2));

    sweeper(Duration.ofHours(24), Duration.ofDays(365)).sweep();

    assertThat(exists(abandoned))
        .as("an abandoned upload is unreferenced by construction")
        .isFalse();
    assertThat(exists(fresh)).as("an in-flight upload must survive").isTrue();
  }

  @Test
  void deletesStoredAttachmentsPastTheRetentionWindow() {
    UUID old = persistAttachment(AttachmentStatus.CLEAN);
    UUID recent = persistAttachment(AttachmentStatus.CLEAN);
    backdate(old, Duration.ofDays(400));

    sweeper(Duration.ofHours(24), Duration.ofDays(365)).sweep();

    assertThat(exists(old)).isFalse();
    assertThat(exists(recent)).isTrue();
  }

  @Test
  void keepsRejectedAndInfectedRowsAsARecordEvenWhenOld() {
    UUID rejected = persistAttachment(AttachmentStatus.REJECTED);
    UUID infected = persistAttachment(AttachmentStatus.INFECTED);
    backdate(rejected, Duration.ofDays(400));
    backdate(infected, Duration.ofDays(400));

    sweeper(Duration.ofHours(24), Duration.ofDays(365)).sweep();

    // Their objects are already gone — deleted at the moment of rejection. The rows are the only
    // evidence that someone uploaded an executable or a virus, so they are not swept.
    assertThat(exists(rejected)).isTrue();
    assertThat(exists(infected)).isTrue();
  }

  private AttachmentSweeper sweeper(Duration abandonedAfter, Duration retention) {
    StorageProperties properties = new StorageProperties();
    properties.getSweeper().setAbandonedAfter(abandonedAfter);
    properties.getSweeper().setRetention(retention);
    return new AttachmentSweeper(attachmentRepository, storage, tx, properties);
  }

  private boolean exists(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists(select 1 from attachments where id = ?)", Boolean.class, id));
  }

  private void backdate(UUID id, Duration age) {
    jdbc.update(
        "update attachments set created_at = ? where id = ?",
        java.sql.Timestamp.from(Instant.now().minus(age)),
        id);
  }

  private UUID persistAttachment(AttachmentStatus status) {
    return tx.execute(
        s -> {
          Attachment attachment =
              new Attachment(
                  schoolId,
                  uploaderId,
                  AttachmentKeys.forAttachment(schoolId, UUID.randomUUID(), Instant.now()),
                  "file.png",
                  "image/png",
                  128L);
          switch (status) {
            case PENDING -> {
              /* already PENDING from the constructor */
            }
            case UPLOADED -> attachment.markUploaded(128L);
            case CLEAN -> {
              attachment.markUploaded(128L);
              attachment.markClean("image/png", AvResult.SKIPPED, Instant.now());
            }
            case REJECTED -> attachment.markRejected("test", Instant.now());
            case INFECTED -> attachment.markInfected("Test-Signature", Instant.now());
          }
          return attachmentRepository.save(attachment).getId();
        });
  }

  private UUID persistSchool() {
    return tx.execute(
        s ->
            schoolRepository
                .save(
                    new School(
                        "Sweeper School",
                        "EG",
                        "Africa/Cairo",
                        "ar-EG",
                        SubscriptionTier.STANDARD,
                        SchoolSettings.defaults()))
                .getId());
  }

  private UUID persistAdmin() {
    return tx.execute(
        s ->
            userRepository
                .save(
                    User.staff(
                        schoolId,
                        UserRole.SCHOOL_ADMIN,
                        "Admin",
                        "admin@sweeper.test",
                        passwordEncoder.encode("pass")))
                .getId());
  }
}
