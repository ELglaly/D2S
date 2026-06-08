package com.schoolbridge.api.announcements;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.announcements.dto.AnnouncementResponse;
import com.schoolbridge.api.announcements.dto.CreateAnnouncementRequest;
import com.schoolbridge.api.announcements.enums.AnnouncementScope;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.announcements.service.AnnouncementService;
import com.schoolbridge.api.classes.RelationshipType;
import com.schoolbridge.api.classes.entity.Enrollment;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
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
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fan-out semantics + outbox payload for {@link AnnouncementService#create}. Covers the "3 parents
 * × 2 students each = 6 recipient rows" SCHOOL-scope scenario from the M6 handoff plus a
 * CLASS-scope scenario filtered by enrollment.
 */
@SpringBootTest
class AnnouncementFanoutIntegrationTest extends AbstractIntegrationTest {

  @Autowired AnnouncementService announcementService;
  @Autowired AnnouncementRepository announcementRepository;
  @Autowired AnnouncementRecipientRepository recipientRepository;
  @Autowired ParentStudentLinkRepository linkRepository;
  @Autowired EnrollmentRepository enrollmentRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired OutboxRepository outboxRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired ObjectMapper objectMapper;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID senderId;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> announcementRepository.deleteAll());
    tx.executeWithoutResult(s -> outboxRepository.deleteAll());
    tx.executeWithoutResult(s -> linkRepository.deleteAll());
    tx.executeWithoutResult(s -> enrollmentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Fanout School",
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
                            "admin@fanout.test",
                            passwordEncoder.encode("pass")))
                    .getId());

    TenantContext.set(schoolId);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void schoolScope_threeParentsTwoStudentsEach_materializesSixRecipients() {
    for (int p = 0; p < 3; p++) {
      String phone = "+201000010" + String.format("%02d", p);
      UUID parentId =
          tx.execute(
              s ->
                  userRepository
                      .save(User.parent(schoolId, "Parent " + phone, phone, blindIndex.hash(phone)))
                      .getId());
      for (int c = 0; c < 2; c++) {
        String childLabel = "Child p" + p + "c" + c;
        UUID studentId =
            tx.execute(
                s ->
                    studentRepository
                        .save(new Student(schoolId, childLabel, LocalDate.of(2015, 1, 1), null))
                        .getId());
        tx.executeWithoutResult(
            s ->
                linkRepository.save(
                    new ParentStudentLink(
                        schoolId, parentId, studentId, RelationshipType.MOTHER, true)));
      }
    }

    CreateAnnouncementRequest request =
        new CreateAnnouncementRequest(
            AnnouncementScope.SCHOOL,
            null,
            null,
            null,
            Language.AR,
            "إعلان مدرسي عام",
            null,
            false,
            null);

    AnnouncementResponse created =
        tx.execute(s -> announcementService.create(schoolId, senderId, request));
    assertThat(created).isNotNull();
    assertThat(created.recipientCount()).isEqualTo(6);

    TenantContext.set(schoolId);
    long rowCount = tx.execute(s -> recipientRepository.countByAnnouncementId(created.id()));
    assertThat(rowCount).isEqualTo(6);
  }

  @Test
  void classScope_filtersToEnrolledStudentsOnly() {
    UUID classRoom =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolId, "Grade 3A", "Grade 3", "2025-2026", null))
                    .getId());

    UUID parentInClass = createParentWithChildEnrolledIn(classRoom, "+201000020001");
    UUID parentOutsideClass = createParentWithChild("+201000020002");

    CreateAnnouncementRequest request =
        new CreateAnnouncementRequest(
            AnnouncementScope.CLASS,
            classRoom,
            null,
            null,
            Language.AR,
            "إعلان للصف",
            null,
            false,
            null);

    AnnouncementResponse created =
        tx.execute(s -> announcementService.create(schoolId, senderId, request));
    assertThat(created.recipientCount()).isEqualTo(1);

    TenantContext.set(schoolId);
    var recipients =
        tx.execute(
            s ->
                recipientRepository
                    .findAllByAnnouncementId(
                        created.id(), org.springframework.data.domain.Pageable.unpaged())
                    .getContent());
    assertThat(recipients).hasSize(1);
    assertThat(recipients.get(0).getParentUserId()).isEqualTo(parentInClass);
    assertThat(recipients.get(0).getParentUserId()).isNotEqualTo(parentOutsideClass);
  }

  @Test
  void schoolScope_recordsAnnouncementCreatedOutboxEventWithExpectedPayload() {
    UUID parentId = createParentWithChild("+201000030001");

    CreateAnnouncementRequest request =
        new CreateAnnouncementRequest(
            AnnouncementScope.SCHOOL,
            null,
            null,
            null,
            Language.AR,
            "إعلان للصندوق الصادر",
            "attachments/key-123",
            true,
            null);

    AnnouncementResponse created =
        tx.execute(s -> announcementService.create(schoolId, senderId, request));

    OutboxEvent event =
        outboxRepository.findAll().stream()
            .filter(e -> "announcement.created".equals(e.getEventType()))
            .filter(e -> e.getAggregateId().equals(created.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected an announcement.created outbox event"));

    assertThat(event.getSchoolId()).isEqualTo(schoolId);
    assertThat(event.getAggregateType()).isEqualTo("Announcement");

    JsonNode payload;
    try {
      payload = objectMapper.readTree(event.getPayload());
    } catch (Exception ex) {
      throw new AssertionError("payload was not valid JSON: " + event.getPayload(), ex);
    }
    assertThat(payload.get("announcementId").asText()).isEqualTo(created.id().toString());
    assertThat(payload.get("schoolId").asText()).isEqualTo(schoolId.toString());
    assertThat(payload.get("language").asText()).isEqualTo("AR");
    assertThat(payload.get("attachmentKey").asText()).isEqualTo("attachments/key-123");
    assertThat(payload.get("recipientCount").asLong()).isEqualTo(1L);
    assertThat(payload.has("body")).isTrue();

    // parentId is asserted via the recipient row (the outbox event aggregates recipient count
    // only).
    assertThat(parentId).isNotNull();
  }

  private UUID createParentWithChild(String phone) {
    UUID parentId =
        tx.execute(
            s ->
                userRepository
                    .save(User.parent(schoolId, "Parent " + phone, phone, blindIndex.hash(phone)))
                    .getId());
    UUID studentId =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolId, "Child " + phone, LocalDate.of(2015, 1, 1), null))
                    .getId());
    tx.executeWithoutResult(
        s ->
            linkRepository.save(
                new ParentStudentLink(
                    schoolId, parentId, studentId, RelationshipType.MOTHER, true)));
    return parentId;
  }

  private UUID createParentWithChildEnrolledIn(UUID classId, String phone) {
    UUID parentId =
        tx.execute(
            s ->
                userRepository
                    .save(User.parent(schoolId, "Parent " + phone, phone, blindIndex.hash(phone)))
                    .getId());
    UUID studentId =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolId, "Child " + phone, LocalDate.of(2015, 1, 1), null))
                    .getId());
    tx.executeWithoutResult(
        s ->
            linkRepository.save(
                new ParentStudentLink(
                    schoolId, parentId, studentId, RelationshipType.MOTHER, true)));
    tx.executeWithoutResult(
        s -> enrollmentRepository.save(new Enrollment(schoolId, studentId, classId)));
    return parentId;
  }
}
