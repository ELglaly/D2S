package com.schoolbridge.api.announcements;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.announcements.enums.AnnouncementScope;
import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Cross-tenant invisibility suite for {@link AnnouncementRepository}. */
@SpringBootTest
class AnnouncementRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired AnnouncementRepository announcementRepository;
  @Autowired AnnouncementRecipientRepository recipientRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;
  private UUID announcementInA;
  private UUID announcementInB;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> announcementRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");

    UUID senderA = persistAdmin(schoolA, "admin-a@iso.test");
    UUID senderB = persistAdmin(schoolB, "admin-b@iso.test");

    announcementInA =
        tx.execute(
            s ->
                announcementRepository
                    .save(
                        new Announcement(
                            schoolA,
                            senderA,
                            AnnouncementScope.SCHOOL,
                            null,
                            Language.AR,
                            "إعلان أ",
                            null,
                            false,
                            null,
                            AnnouncementStatus.SENT))
                    .getId());

    announcementInB =
        tx.execute(
            s ->
                announcementRepository
                    .save(
                        new Announcement(
                            schoolB,
                            senderB,
                            AnnouncementScope.SCHOOL,
                            null,
                            Language.AR,
                            "إعلان ب",
                            null,
                            false,
                            null,
                            AnnouncementStatus.SENT))
                    .getId());
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findById_underTenantA_cannotSeeAnnouncementInB() {
    TenantContext.set(schoolA);
    var own = tx.execute(s -> announcementRepository.findById(announcementInA));
    var other = tx.execute(s -> announcementRepository.findById(announcementInB));
    assertThat(own).isPresent();
    assertThat(other).as("school A must not see school B's announcement").isEmpty();
  }

  @Test
  void findAll_underTenantB_returnsOnlyOwnRows() {
    TenantContext.set(schoolB);
    var all = tx.execute(s -> announcementRepository.findAll());
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getId()).isEqualTo(announcementInB);
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

  private UUID persistAdmin(UUID schoolId, String email) {
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
}
