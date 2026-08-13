package com.schoolbridge.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.announcements.Announcement;
import com.schoolbridge.api.announcements.AnnouncementRecipient;
import com.schoolbridge.api.announcements.enums.AnnouncementScope;
import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import com.schoolbridge.api.announcements.enums.DeliveryStatus;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.integrations.AnnouncementDeferralSweeper;
import com.schoolbridge.api.integrations.AnnouncementSendService;
import com.schoolbridge.api.integrations.NotificationChannel;
import com.schoolbridge.api.integrations.whatsapp.FakeWhatsAppClient;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * The end-to-end quiet-hours path for announcements: held at fan-out, released by the sweeper.
 *
 * <p>Announcements are the highest-volume parent-facing message and, until this change, the only
 * one with no deferral path at all — so this is the case that actually stops a 22:00 notification.
 */
@SpringBootTest
class AnnouncementDeferralIntegrationTest extends AbstractIntegrationTest {

  private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

  @Autowired AnnouncementSendService sendService;
  @Autowired AnnouncementDeferralSweeper sweeper;
  @Autowired AnnouncementRepository announcementRepository;
  @Autowired AnnouncementRecipientRepository recipientRepository;
  @Autowired NotificationSettingsRepository settingsRepository;
  @Autowired NotificationPreferenceRepository preferenceRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired FakeWhatsAppClient fakeWhatsApp;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  private UUID schoolId;
  private UUID parentId;
  private UUID announcementId;

  @BeforeEach
  void setUp() {
    TenantContext.clear();
    fakeWhatsApp.reset();
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> announcementRepository.deleteAll());
    tx.executeWithoutResult(s -> preferenceRepository.deleteAll());
    tx.executeWithoutResult(s -> settingsRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Deferral School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());
    TenantContext.set(schoolId);

    UUID senderId = persistUser("admin@deferral.test", UserRole.SCHOOL_ADMIN, null);
    parentId = persistUser("parent@deferral.test", UserRole.PARENT, "+201000500001");
    UUID studentId =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolId, "Adam Hassan", LocalDate.of(2015, 5, 1), "S-1"))
                    .getId());

    announcementId =
        tx.execute(
            s ->
                announcementRepository
                    .save(
                        new Announcement(
                            schoolId,
                            senderId,
                            AnnouncementScope.SCHOOL,
                            null,
                            Language.EN,
                            "Parent evening moved to Thursday.",
                            null,
                            false,
                            null,
                            AnnouncementStatus.SENT))
                    .getId());
    tx.executeWithoutResult(
        s ->
            recipientRepository.save(
                new AnnouncementRecipient(schoolId, announcementId, parentId, studentId)));
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void insideTheParentsQuietWindow_theRecipientIsHeldRatherThanSent() {
    givenQuietHoursCoveringNow();

    dispatch();

    AnnouncementRecipient row = onlyRecipient();
    assertThat(row.getDeliveryStatus()).isEqualTo(DeliveryStatus.DEFERRED);
    assertThat(row.getDeferredUntil()).isNotNull();
    assertThat(row.getMessageId()).isNull();
    assertThat(fakeWhatsApp.sent()).as("nothing may reach the provider while held").isEmpty();
  }

  @Test
  void onceTheHoldExpires_theSweeperReleasesAndSendsIt() {
    givenQuietHoursCoveringNow();
    dispatch();
    expireTheHold();

    TenantContext.clear();
    sweeper.releaseDeferredRecipients();
    TenantContext.set(schoolId);

    AnnouncementRecipient row = onlyRecipient();
    assertThat(row.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    assertThat(row.getMessageId()).isNotNull();
    assertThat(row.getDeferredUntil()).isNull();
  }

  @Test
  void aParentWhoOptedOutIsSuppressed_notFailed() {
    tx.executeWithoutResult(
        s ->
            preferenceRepository.save(
                new NotificationPreference(
                    schoolId,
                    parentId,
                    NotificationCategory.ANNOUNCEMENT,
                    false,
                    List.of(NotificationChannel.WHATSAPP))));

    dispatch();

    AnnouncementRecipient row = onlyRecipient();
    assertThat(row.getDeliveryStatus())
        .as("an honoured opt-out is not a delivery failure and must not be counted as one")
        .isEqualTo(DeliveryStatus.SUPPRESSED);
    assertThat(fakeWhatsApp.sent()).isEmpty();
  }

  @Test
  void anAnnouncementRecalledDuringTheHold_isNotSentWhenTheHoldExpires() {
    givenQuietHoursCoveringNow();
    dispatch();
    expireTheHold();
    tx.executeWithoutResult(
        s -> announcementRepository.findById(announcementId).orElseThrow().recall());

    TenantContext.clear();
    sweeper.releaseDeferredRecipients();
    TenantContext.set(schoolId);

    assertThat(onlyRecipient().getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(fakeWhatsApp.sent()).isEmpty();
  }

  private void dispatch() {
    tx.executeWithoutResult(
        s ->
            sendService.dispatchCreated(
                announcementId, Language.EN, "Parent evening moved to Thursday."));
  }

  /**
   * A window centred on the current wall clock, so the test does not depend on when it is run. The
   * calculator handles the wrap-around case, which is what this becomes near midnight.
   */
  private void givenQuietHoursCoveringNow() {
    LocalTime localNow = ZonedDateTime.ofInstant(Instant.now(), CAIRO).toLocalTime();
    tx.executeWithoutResult(
        s ->
            settingsRepository.save(
                new NotificationSettings(
                    schoolId,
                    parentId,
                    true,
                    localNow.minusHours(1).truncatedTo(ChronoUnit.MINUTES),
                    localNow.plusHours(1).truncatedTo(ChronoUnit.MINUTES))));
  }

  /** Pull the hold into the past so the sweeper's due-scan picks the row up on this tick. */
  private void expireTheHold() {
    jdbc.update(
        "update announcement_recipients set deferred_until = ? where announcement_id = ?",
        java.sql.Timestamp.from(Instant.now().minus(5, ChronoUnit.MINUTES)),
        announcementId);
  }

  private AnnouncementRecipient onlyRecipient() {
    List<AnnouncementRecipient> rows = tx.execute(s -> recipientRepository.findAll());
    assertThat(rows).hasSize(1);
    return rows.get(0);
  }

  private UUID persistUser(String email, UserRole role, String phone) {
    return tx.execute(
        s -> {
          User user =
              role == UserRole.PARENT
                  ? User.parent(schoolId, "Parent " + email, phone, phone)
                  : User.staff(schoolId, role, "Staff", email, passwordEncoder.encode("pass"));
          return userRepository.save(user).getId();
        });
  }
}
