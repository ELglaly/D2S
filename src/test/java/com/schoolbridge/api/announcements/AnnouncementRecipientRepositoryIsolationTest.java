package com.schoolbridge.api.announcements;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.announcements.enums.AnnouncementScope;
import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Cross-tenant invisibility suite for {@link AnnouncementRecipientRepository}. */
@SpringBootTest
class AnnouncementRecipientRepositoryIsolationTest extends AbstractIntegrationTest {

  @Autowired AnnouncementRepository announcementRepository;
  @Autowired AnnouncementRecipientRepository recipientRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;
  private UUID announcementA;
  private UUID announcementB;
  private UUID recipientInA;
  private UUID recipientInB;
  private UUID parentInA;
  private UUID parentInB;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> announcementRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");

    UUID adminA = persistAdmin(schoolA, "admin-a@recipient-iso.test");
    UUID adminB = persistAdmin(schoolB, "admin-b@recipient-iso.test");

    parentInA = persistParent(schoolA, "+201234500001");
    parentInB = persistParent(schoolB, "+201234500002");

    UUID studentA =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolA, "Child A", LocalDate.of(2015, 1, 1), null))
                    .getId());
    UUID studentB =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolB, "Child B", LocalDate.of(2015, 1, 1), null))
                    .getId());

    announcementA =
        tx.execute(
            s ->
                announcementRepository
                    .save(
                        new Announcement(
                            schoolA,
                            adminA,
                            AnnouncementScope.SCHOOL,
                            null,
                            Language.AR,
                            "إعلان أ",
                            null,
                            false,
                            null,
                            AnnouncementStatus.SENT))
                    .getId());
    announcementB =
        tx.execute(
            s ->
                announcementRepository
                    .save(
                        new Announcement(
                            schoolB,
                            adminB,
                            AnnouncementScope.SCHOOL,
                            null,
                            Language.AR,
                            "إعلان ب",
                            null,
                            false,
                            null,
                            AnnouncementStatus.SENT))
                    .getId());

    recipientInA =
        tx.execute(
            s ->
                recipientRepository
                    .save(new AnnouncementRecipient(schoolA, announcementA, parentInA, studentA))
                    .getId());
    recipientInB =
        tx.execute(
            s ->
                recipientRepository
                    .save(new AnnouncementRecipient(schoolB, announcementB, parentInB, studentB))
                    .getId());
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void findById_underTenantA_cannotSeeRecipientInB() {
    TenantContext.set(schoolA);
    var own = tx.execute(s -> recipientRepository.findById(recipientInA));
    var other = tx.execute(s -> recipientRepository.findById(recipientInB));
    assertThat(own).isPresent();
    assertThat(other).as("school A must not see school B's recipient").isEmpty();
  }

  @Test
  void findAllByAnnouncementId_isFilteredByTenant() {
    TenantContext.set(schoolA);
    var underA =
        tx.execute(
            s -> recipientRepository.findAllByAnnouncementId(announcementB, Pageable.unpaged()));
    assertThat(underA.getTotalElements())
        .as("school A querying school B's announcement must see zero rows")
        .isZero();
  }

  @Test
  void existsByAnnouncementIdAndParentUserId_isFilteredByTenant() {
    TenantContext.set(schoolA);
    boolean acrossTenants =
        Boolean.TRUE.equals(
            tx.execute(
                s ->
                    recipientRepository.existsByAnnouncementIdAndParentUserId(
                        announcementB, parentInB)));
    assertThat(acrossTenants).as("school A must not see school B's recipient via exists").isFalse();
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

  private UUID persistParent(UUID schoolId, String phone) {
    return tx.execute(
        s ->
            userRepository
                .save(User.parent(schoolId, "Parent " + phone, phone, blindIndex.hash(phone)))
                .getId());
  }
}
