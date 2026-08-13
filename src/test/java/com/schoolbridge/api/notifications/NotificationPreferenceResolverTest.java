package com.schoolbridge.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.integrations.NotificationChannel;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The resolver's decision table. Runs against a real database rather than mocks because the
 * fallback chain — user row, then school row, then hard default — is the part most likely to break,
 * and mocking the repositories would assert the chain I wrote rather than the one that runs.
 */
@SpringBootTest
class NotificationPreferenceResolverTest extends AbstractIntegrationTest {

  private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

  @Autowired NotificationPreferenceService service;
  @Autowired NotificationPreferenceRepository preferenceRepository;
  @Autowired NotificationSettingsRepository settingsRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID userId;

  @BeforeEach
  void setUp() {
    TenantContext.clear();
    tx.executeWithoutResult(s -> preferenceRepository.deleteAll());
    tx.executeWithoutResult(s -> settingsRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    // School defaults: quiet hours 21:00–07:00 Cairo, but alertsRespectQuietHours = false.
    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Resolver School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());
    userId =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.staff(
                            schoolId,
                            UserRole.SCHOOL_ADMIN,
                            "Parent",
                            "parent@resolver.test",
                            passwordEncoder.encode("pass")))
                    .getId());
    TenantContext.set(schoolId);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void noRowsAtAll_sendsNowOnTheDefaultChannelOrder() {
    NotificationDecision decision = resolve(NotificationCategory.HOMEWORK, cairo(23, 0));

    assertThat(decision.suppressed()).isFalse();
    assertThat(decision.deferred())
        .as("the school flag is off, so an untouched user keeps the pre-feature behaviour")
        .isFalse();
    assertThat(decision.channels())
        .containsExactly(NotificationChannel.DEFAULT_ORDER.toArray(new NotificationChannel[0]));
  }

  @Test
  void optedOutCategory_isSuppressed() {
    savePreference(NotificationCategory.ANNOUNCEMENT, false, List.of(NotificationChannel.PUSH));

    NotificationDecision decision = resolve(NotificationCategory.ANNOUNCEMENT, cairo(12, 0));

    assertThat(decision.suppressed()).isTrue();
    assertThat(decision.deferred()).isFalse();
  }

  @Test
  void insideTheUsersOwnWindow_defersUntilItCloses() {
    saveSettings(true, LocalTime.of(22, 0), LocalTime.of(6, 0));

    NotificationDecision decision = resolve(NotificationCategory.HOMEWORK, cairo(23, 30));

    assertThat(decision.suppressed()).isFalse();
    assertThat(decision.deferred()).isTrue();
    // 23:30 is in the after-start half of a wrap-around window, so it closes tomorrow morning.
    assertThat(decision.deferUntil())
        .isEqualTo(ZonedDateTime.of(2026, 3, 11, 6, 0, 0, 0, CAIRO).toInstant());
  }

  @Test
  void theUsersWindowOverridesTheSchools() {
    // School window is 21:00–07:00; the user narrowed theirs to 23:00–05:00. At 22:00 the school
    // would have held the message and the user would not.
    saveSettings(true, LocalTime.of(23, 0), LocalTime.of(5, 0));

    NotificationDecision decision = resolve(NotificationCategory.HOMEWORK, cairo(22, 0));

    assertThat(decision.deferred()).as("the user's narrower window wins").isFalse();
  }

  @Test
  void respectingQuietHoursWithNoOwnWindow_inheritsTheSchools() {
    // Null start/end means inherit, so this user is held by the school's 21:00–07:00 window.
    saveSettings(true, null, null);

    NotificationDecision decision = resolve(NotificationCategory.HOMEWORK, cairo(22, 0));

    assertThat(decision.deferred()).isTrue();
    assertThat(decision.deferUntil())
        .isEqualTo(ZonedDateTime.of(2026, 3, 11, 7, 0, 0, 0, CAIRO).toInstant());
  }

  @Test
  void attendanceIgnoresAnExplicitOptOutRowAndTheQuietWindow() {
    // The API rejects this combination, so write it straight to the table: the guarantee has to
    // hold against a row that exists however it got there — a bad migration, a fixture, a bug in a
    // future endpoint — not merely against the endpoint that refuses to create it.
    savePreference(NotificationCategory.ATTENDANCE, false, List.of(NotificationChannel.PUSH));
    saveSettings(true, LocalTime.of(21, 0), LocalTime.of(7, 0));

    NotificationDecision decision = resolve(NotificationCategory.ATTENDANCE, cairo(23, 0));

    assertThat(decision.suppressed()).as("an absence alert can never be muted").isFalse();
    assertThat(decision.deferred()).as("an absence alert can never be held").isFalse();
  }

  @Test
  void attendanceHonoursChannelOrderButNeverDropsAChannel() {
    savePreference(NotificationCategory.ATTENDANCE, true, List.of(NotificationChannel.SMS));

    NotificationDecision decision = resolve(NotificationCategory.ATTENDANCE, cairo(9, 0));

    assertThat(decision.channels())
        .as("the user's choice leads, but the channels they left out are still available")
        .containsExactly(
            NotificationChannel.SMS, NotificationChannel.PUSH, NotificationChannel.WHATSAPP);
  }

  @Test
  void mutableCategoryDoesDropChannelsTheUserRemoved() {
    savePreference(NotificationCategory.HOMEWORK, true, List.of(NotificationChannel.PUSH));

    NotificationDecision decision = resolve(NotificationCategory.HOMEWORK, cairo(9, 0));

    assertThat(decision.channels())
        .as("for a mutable category, removing a channel is a real preference")
        .containsExactly(NotificationChannel.PUSH);
  }

  private NotificationDecision resolve(NotificationCategory category, Instant now) {
    return tx.execute(s -> service.resolve(schoolId, userId, category, now));
  }

  private static Instant cairo(int hour, int minute) {
    return ZonedDateTime.of(2026, 3, 10, hour, minute, 0, 0, CAIRO).toInstant();
  }

  private void savePreference(
      NotificationCategory category, boolean enabled, List<NotificationChannel> channels) {
    tx.executeWithoutResult(
        s ->
            preferenceRepository.save(
                new NotificationPreference(schoolId, userId, category, enabled, channels)));
  }

  private void saveSettings(boolean respect, LocalTime start, LocalTime end) {
    tx.executeWithoutResult(
        s ->
            settingsRepository.save(
                new NotificationSettings(schoolId, userId, respect, start, end)));
  }
}
