package com.schoolbridge.api.announcements;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.announcements.dto.AnnouncementResponse;
import com.schoolbridge.api.announcements.dto.CreateAnnouncementRequest;
import com.schoolbridge.api.announcements.enums.AnnouncementScope;
import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.announcements.service.AnnouncementService;
import com.schoolbridge.api.classes.RelationshipType;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.common.outbox.OutboxEvent;
import com.schoolbridge.api.common.outbox.OutboxRepository;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 * Scheduled announcements must not be delivered before their time, and must be delivered when it
 * arrives.
 *
 * <p>Both halves were broken: {@code create} recorded the dispatch event unconditionally, so a
 * scheduled announcement went out immediately while showing SCHEDULED, and no sweeper existed to
 * release it later. The first test is the regression guard; the second proves the release path.
 */
@SpringBootTest(properties = "schoolbridge.announcements.sweeper.enabled=true")
class AnnouncementScheduleSweeperIntegrationTest extends AbstractIntegrationTest {

  @Autowired AnnouncementService announcementService;
  @Autowired AnnouncementRepository announcementRepository;
  @Autowired AnnouncementRecipientRepository recipientRepository;
  @Autowired ParentStudentLinkRepository linkRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired OutboxRepository outboxRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired AnnouncementScheduleSweeper sweeper;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID senderId;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> announcementRepository.deleteAll());
    tx.executeWithoutResult(s -> outboxRepository.deleteAll());
    tx.executeWithoutResult(s -> linkRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Schedule School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());
    senderId =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.staff(
                            schoolId,
                            UserRole.SCHOOL_ADMIN,
                            "Admin",
                            "admin@schedule.test",
                            passwordEncoder.encode("pass")))
                    .getId());

    UUID parentId =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.parent(
                            schoolId, "Parent", "+201099000001", blindIndex.hash("+201099000001")))
                    .getId());
    UUID studentId =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolId, "Child", LocalDate.of(2015, 1, 1), null))
                    .getId());
    tx.executeWithoutResult(
        s ->
            linkRepository.save(
                new ParentStudentLink(
                    schoolId, parentId, studentId, RelationshipType.MOTHER, true)));
    TenantContext.set(schoolId);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private AnnouncementResponse createScheduledFor(Instant when) {
    return tx.execute(
        s ->
            announcementService.create(
                schoolId,
                senderId,
                new CreateAnnouncementRequest(
                    AnnouncementScope.SCHOOL,
                    null,
                    null,
                    null,
                    Language.AR,
                    "إعلان مجدول",
                    null,
                    false,
                    when)));
  }

  private List<OutboxEvent> dispatchEvents() {
    return tx.execute(
        s ->
            outboxRepository.findAll().stream()
                .filter(e -> "announcement.created".equals(e.getEventType()))
                .toList());
  }

  @Test
  void futureScheduledAnnouncementIsNotDispatchedOnCreate() {
    AnnouncementResponse created = createScheduledFor(Instant.now().plus(7, ChronoUnit.DAYS));

    assertThat(created.status()).isEqualTo(AnnouncementStatus.SCHEDULED);
    // Recipients are materialised up front — only the dispatch is deferred.
    assertThat(recipientRepository.countByAnnouncementId(created.id())).isEqualTo(1);
    assertThat(dispatchEvents())
        .as("scheduling must actually defer delivery, not just label it")
        .isEmpty();

    sweeper.releaseDueAnnouncements();
    TenantContext.set(schoolId);

    assertThat(dispatchEvents()).as("still not due").isEmpty();
    assertThat(announcementRepository.findById(created.id()).orElseThrow().getStatus())
        .isEqualTo(AnnouncementStatus.SCHEDULED);
  }

  @Test
  void dueScheduledAnnouncementIsReleasedBySweeper() {
    AnnouncementResponse created = createScheduledFor(Instant.now().minus(1, ChronoUnit.MINUTES));
    assertThat(dispatchEvents()).isEmpty();

    sweeper.releaseDueAnnouncements();
    TenantContext.set(schoolId);

    assertThat(dispatchEvents()).as("a due announcement must be dispatched").hasSize(1);
    assertThat(announcementRepository.findById(created.id()).orElseThrow().getStatus())
        .isEqualTo(AnnouncementStatus.SENT);
  }

  @Test
  void unscheduledAnnouncementStillDispatchesImmediately() {
    AnnouncementResponse created = createScheduledFor(null);

    assertThat(created.status()).isEqualTo(AnnouncementStatus.SENT);
    assertThat(dispatchEvents()).as("the normal path must be unaffected").hasSize(1);
  }

  @Test
  void sweeperIsIdempotentAcrossRuns() {
    createScheduledFor(Instant.now().minus(1, ChronoUnit.MINUTES));

    sweeper.releaseDueAnnouncements();
    sweeper.releaseDueAnnouncements();
    TenantContext.set(schoolId);

    assertThat(dispatchEvents())
        .as("a second sweep must not double-send — parents would receive it twice")
        .hasSize(1);
  }
}
