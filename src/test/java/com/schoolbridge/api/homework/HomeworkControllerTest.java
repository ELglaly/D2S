package com.schoolbridge.api.homework;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.AbstractIntegrationTest;
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
import com.schoolbridge.api.common.outbox.OutboxRepository;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.jwt.JwtService;
import com.schoolbridge.api.identity.otp.OtpService;
import com.schoolbridge.api.subjects.ClassSubject;
import com.schoolbridge.api.subjects.ClassSubjectRepository;
import com.schoolbridge.api.subjects.Subject;
import com.schoolbridge.api.subjects.SubjectRepository;
import com.schoolbridge.api.subjects.TeacherSubjectAssignment;
import com.schoolbridge.api.subjects.TeacherSubjectAssignmentRepository;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REST-layer matrix test for {@link HomeworkController}. Fan-out semantics and reminder pipeline
 * are covered in BP-2 by {@code HomeworkReminderServiceIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HomeworkControllerTest extends AbstractIntegrationTest {

  @LocalServerPort int port;

  @Autowired HomeworkItemRepository homeworkItemRepository;
  @Autowired HomeworkRecipientRepository recipientRepository;
  @Autowired EnrollmentRepository enrollmentRepository;
  @Autowired ParentStudentLinkRepository linkRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired TeacherSubjectAssignmentRepository teacherSubjectAssignmentRepository;
  @Autowired ClassSubjectRepository classSubjectRepository;
  @Autowired SubjectRepository subjectRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired OutboxRepository outboxRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired JwtService jwtService;
  @Autowired OtpService otpService;
  @Autowired TransactionTemplate tx;

  private static final LocalDate DUE = LocalDate.now().plusDays(3);

  private UUID schoolId;
  private UUID classTaught;
  private UUID classOther;
  private UUID teacherId;
  private UUID otherTeacherId;
  private UUID studentId;
  private UUID parentId;
  private UUID unlinkedParentId;
  private String adminToken;
  private String teacherToken;
  private String otherTeacherToken;
  private String parentToken;
  private String unlinkedParentToken;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> homeworkItemRepository.deleteAll());
    tx.executeWithoutResult(s -> enrollmentRepository.deleteAll());
    tx.executeWithoutResult(s -> linkRepository.deleteAll());
    tx.executeWithoutResult(s -> teacherSubjectAssignmentRepository.deleteAll());
    tx.executeWithoutResult(s -> classSubjectRepository.deleteAll());
    tx.executeWithoutResult(s -> subjectRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> outboxRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "HW Test School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());

    UUID adminId = persistStaff(UserRole.SCHOOL_ADMIN, "admin@hw-ctrl.test", "Admin");
    teacherId = persistStaff(UserRole.TEACHER, "teacher@hw-ctrl.test", "Teacher");
    otherTeacherId = persistStaff(UserRole.TEACHER, "other@hw-ctrl.test", "Other Teacher");

    classTaught =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolId, "3A", "Grade 3", "2025-2026", null))
                    .getId());
    classOther =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolId, "4B", "Grade 4", "2025-2026", null))
                    .getId());

    UUID mathSubjectId =
        tx.execute(
            s -> subjectRepository.save(new Subject(schoolId, "Mathematics", null, null)).getId());
    tx.executeWithoutResult(
        s -> classSubjectRepository.save(new ClassSubject(schoolId, classTaught, mathSubjectId)));
    tx.executeWithoutResult(
        s ->
            teacherSubjectAssignmentRepository.save(
                new TeacherSubjectAssignment(schoolId, teacherId, classTaught, mathSubjectId)));

    studentId = persistStudent("Child One");
    tx.executeWithoutResult(
        s -> enrollmentRepository.save(new Enrollment(schoolId, studentId, classTaught)));

    parentId = persistParent("+201010000001");
    unlinkedParentId = persistParent("+201010000002");
    tx.executeWithoutResult(
        s ->
            linkRepository.save(
                new ParentStudentLink(
                    schoolId, parentId, studentId, RelationshipType.MOTHER, true)));

    adminToken = issueStaffToken(adminId, UserRole.SCHOOL_ADMIN);
    teacherToken = issueStaffToken(teacherId, UserRole.TEACHER);
    otherTeacherToken = issueStaffToken(otherTeacherId, UserRole.TEACHER);
    parentToken = issueParentToken(parentId);
    unlinkedParentToken = issueParentToken(unlinkedParentId);
  }

  // --- create ---------------------------------------------------------------

  @Test
  void create_withAdmin_draft_returns201_statusDraft() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(baseBody(classTaught, false))
        .when()
        .post("/api/v1/homework")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .body("data.status", equalTo("DRAFT"))
        .body("data.recipientCount", equalTo(0));
  }

  @Test
  void create_withAdmin_publish_returns201_statusPublished_andMaterialisesRecipients() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(baseBody(classTaught, true))
        .when()
        .post("/api/v1/homework")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .body("data.status", equalTo("PUBLISHED"))
        .body("data.recipientCount", equalTo(1));

    assertThat(countOutboxByType("homework.published")).isEqualTo(1);
  }

  @Test
  void create_withTeacher_ownClass_returns201() {
    given()
        .header("Authorization", "Bearer " + teacherToken)
        .contentType(ContentType.JSON)
        .body(baseBody(classTaught, false))
        .when()
        .post("/api/v1/homework")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201);
  }

  @Test
  void create_withTeacher_classNotAssigned_returns403() {
    given()
        .header("Authorization", "Bearer " + otherTeacherToken)
        .contentType(ContentType.JSON)
        .body(baseBody(classTaught, false))
        .when()
        .post("/api/v1/homework")
        .then()
        .statusCode(403);
  }

  @Test
  void create_withParent_returns403() {
    given()
        .header("Authorization", "Bearer " + parentToken)
        .contentType(ContentType.JSON)
        .body(baseBody(classTaught, false))
        .when()
        .post("/api/v1/homework")
        .then()
        .statusCode(403);
  }

  // --- publish --------------------------------------------------------------

  @Test
  void publish_draft_returns200_published_andRecipientsMaterialized() {
    String id = createDraft(adminToken, classTaught);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .post("/api/v1/homework/" + id + "/publish")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data.status", equalTo("PUBLISHED"))
        .body("data.recipientCount", equalTo(1));

    assertThat(countOutboxByType("homework.published")).isEqualTo(1);
    long rows =
        tx.execute(s -> recipientRepository.findAllByHomeworkId(UUID.fromString(id)).size());
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void publish_alreadyPublished_returns409() {
    String id = createPublished(adminToken, classTaught);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .post("/api/v1/homework/" + id + "/publish")
        .then()
        .statusCode(409);
  }

  // --- update ---------------------------------------------------------------

  @Test
  void update_draft_returns200_fieldsChanged() {
    String id = createDraft(adminToken, classTaught);

    Map<String, Object> patch = new HashMap<>();
    patch.put("subject", "Updated Subject");
    patch.put("description", "Updated description text");
    patch.put("dueDate", DUE.plusDays(1).toString());

    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(patch)
        .when()
        .patch("/api/v1/homework/" + id)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data.subject", equalTo("Updated Subject"));
  }

  @Test
  void update_archived_returns409() {
    String id = createDraft(adminToken, classTaught);
    archiveItem(adminToken, id);

    Map<String, Object> patch = new HashMap<>();
    patch.put("subject", "Shouldn't work");
    patch.put("description", "x");
    patch.put("dueDate", DUE.toString());

    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(patch)
        .when()
        .patch("/api/v1/homework/" + id)
        .then()
        .statusCode(409);
  }

  // --- delete (archive) -----------------------------------------------------

  @Test
  void delete_returns204_statusArchived_andOutboxWritten() {
    String id = createDraft(adminToken, classTaught);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .delete("/api/v1/homework/" + id)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(204);

    assertThat(countOutboxByType("homework.archived")).isEqualTo(1);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .get("/api/v1/homework/" + id)
        .then()
        .body("data.status", equalTo("ARCHIVED"));
  }

  // --- parent feed ----------------------------------------------------------

  @Test
  void parentFeed_ownChild_returns200_withEntries() {
    createPublished(adminToken, classTaught);

    given()
        .header("Authorization", "Bearer " + parentToken)
        .queryParam("childId", studentId.toString())
        .when()
        .get("/api/v1/homework")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data[0].homeworkId", notNullValue())
        .body("data[0].deliveryStatus", equalTo("PENDING"));
  }

  @Test
  void parentFeed_unlinkedChild_returns404_notForbidden() {
    given()
        .header("Authorization", "Bearer " + unlinkedParentToken)
        .queryParam("childId", studentId.toString())
        .when()
        .get("/api/v1/homework")
        .then()
        .statusCode(404);
  }

  // --- get-one --------------------------------------------------------------

  @Test
  void getOne_asParent_recipientChild_returns200() {
    String id = createPublished(adminToken, classTaught);

    given()
        .header("Authorization", "Bearer " + parentToken)
        .when()
        .get("/api/v1/homework/" + id)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data.id", equalTo(id));
  }

  @Test
  void getOne_asParent_notRecipient_returns404() {
    String id = createPublished(adminToken, classTaught);

    given()
        .header("Authorization", "Bearer " + unlinkedParentToken)
        .when()
        .get("/api/v1/homework/" + id)
        .then()
        .statusCode(404);
  }

  @Test
  void getOne_notFound_returns404() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .get("/api/v1/homework/" + UUID.randomUUID())
        .then()
        .statusCode(404);
  }

  // --- acknowledge ----------------------------------------------------------

  @Test
  void acknowledge_byRecipientParent_returns204_andSetsAcknowledgedAt() {
    String id = createPublished(adminToken, classTaught);

    given()
        .header("Authorization", "Bearer " + parentToken)
        .when()
        .post("/api/v1/homework/" + id + "/acknowledge")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(204);

    UUID homeworkId = UUID.fromString(id);
    Instant acked =
        tx.execute(
            s ->
                recipientRepository
                    .findFirstByHomeworkIdAndParentUserId(homeworkId, parentId)
                    .map(com.schoolbridge.api.homework.HomeworkRecipient::getAcknowledgedAt)
                    .orElse(null));
    assertThat(acked).isNotNull();
  }

  @Test
  void acknowledge_byNonRecipientParent_returns404() {
    String id = createPublished(adminToken, classTaught);

    given()
        .header("Authorization", "Bearer " + unlinkedParentToken)
        .when()
        .post("/api/v1/homework/" + id + "/acknowledge")
        .then()
        .statusCode(404);
  }

  // --- helpers --------------------------------------------------------------

  private Map<String, Object> baseBody(UUID classId, boolean publish) {
    Map<String, Object> body = new HashMap<>();
    body.put("classId", classId.toString());
    body.put("subject", "Math Chapter 5");
    body.put("description", "Complete exercises 1-10.");
    body.put("dueDate", DUE.toString());
    body.put("requiresAck", false);
    body.put("publish", publish);
    return body;
  }

  private String createDraft(String token, UUID classId) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(baseBody(classId, false))
        .when()
        .post("/api/v1/homework")
        .then()
        .statusCode(201)
        .extract()
        .path("data.id");
  }

  private String createPublished(String token, UUID classId) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(baseBody(classId, true))
        .when()
        .post("/api/v1/homework")
        .then()
        .statusCode(201)
        .extract()
        .path("data.id");
  }

  private void archiveItem(String token, String id) {
    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .delete("/api/v1/homework/" + id)
        .then()
        .statusCode(204);
  }

  private long countOutboxByType(String eventType) {
    return outboxRepository.findAll().stream()
        .filter(e -> eventType.equals(e.getEventType()))
        .count();
  }

  private UUID persistStaff(UserRole role, String email, String name) {
    return tx.execute(
        s ->
            userRepository
                .save(User.staff(schoolId, role, name, email, passwordEncoder.encode("pass")))
                .getId());
  }

  private UUID persistParent(String phone) {
    return tx.execute(
        s ->
            userRepository
                .save(User.parent(schoolId, "Parent " + phone, phone, blindIndex.hash(phone)))
                .getId());
  }

  private UUID persistStudent(String name) {
    return tx.execute(
        s ->
            studentRepository
                .save(new Student(schoolId, name, LocalDate.of(2015, 1, 1), null))
                .getId());
  }

  private String issueStaffToken(UUID userId, UserRole role) {
    return jwtService.issueAccess(
        userId.toString(),
        Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", role.name()));
  }

  private String issueParentToken(UUID parentUserId) {
    OtpService.IssuedOtp ticket = otpService.issue(parentUserId, schoolId);
    OtpService.ParentSession session = otpService.verify(ticket.ticketId(), ticket.code());
    return session.token();
  }
}
