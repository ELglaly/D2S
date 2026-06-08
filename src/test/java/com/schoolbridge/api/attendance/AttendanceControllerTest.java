package com.schoolbridge.api.attendance;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.schoolbridge.api.common.outbox.OutboxEvent;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
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
 * REST-layer matrix test for {@link AttendanceController}: auth, idempotency, transition semantics,
 * bulk aggregation, and parent-response anti-enumeration. NFR-P2 latency + the consumer fan-out are
 * covered in BP-2 by {@code AttendanceAlertConsumerIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttendanceControllerTest extends AbstractIntegrationTest {

  @LocalServerPort int port;

  @Autowired AttendanceRecordRepository attendanceRepository;
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
  @Autowired ObjectMapper objectMapper;
  @Autowired TransactionTemplate tx;

  private static final LocalDate TODAY = LocalDate.of(2026, 5, 31);

  private UUID schoolId;
  private UUID classTaught;
  private UUID classNotTaught;
  private UUID teacherId;
  private UUID otherTeacherId;
  private UUID adminId;
  private UUID studentLinked;
  private UUID studentUnlinked;
  private UUID parentLinkedId;
  private UUID parentUnlinkedId;
  private String adminToken;
  private String teacherToken;
  private String otherTeacherToken;
  private String linkedParentToken;
  private String unlinkedParentToken;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    tx.executeWithoutResult(s -> attendanceRepository.deleteAll());
    tx.executeWithoutResult(s -> enrollmentRepository.deleteAll());
    tx.executeWithoutResult(s -> linkRepository.deleteAll());
    tx.executeWithoutResult(s -> teacherSubjectAssignmentRepository.deleteAll());
    tx.executeWithoutResult(s -> classSubjectRepository.deleteAll());
    tx.executeWithoutResult(s -> subjectRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> outboxRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Attendance Test School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());

    adminId = persistStaff(UserRole.SCHOOL_ADMIN, "admin@att-ctrl.test", "Attendance Admin");
    teacherId = persistStaff(UserRole.TEACHER, "teacher@att-ctrl.test", "Teacher");
    otherTeacherId = persistStaff(UserRole.TEACHER, "other-teacher@att-ctrl.test", "Other Teacher");

    classTaught =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolId, "3A", "Grade 3", "2025-2026", null))
                    .getId());
    classNotTaught =
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

    studentLinked = persistStudent("Linked Child");
    studentUnlinked = persistStudent("Unlinked Child");
    tx.executeWithoutResult(
        s -> enrollmentRepository.save(new Enrollment(schoolId, studentLinked, classTaught)));
    tx.executeWithoutResult(
        s -> enrollmentRepository.save(new Enrollment(schoolId, studentUnlinked, classTaught)));

    parentLinkedId = persistParent("+201090000101");
    parentUnlinkedId = persistParent("+201090000102");
    tx.executeWithoutResult(
        s ->
            linkRepository.save(
                new ParentStudentLink(
                    schoolId, parentLinkedId, studentLinked, RelationshipType.MOTHER, true)));

    adminToken = issueStaffToken(adminId, UserRole.SCHOOL_ADMIN);
    teacherToken = issueStaffToken(teacherId, UserRole.TEACHER);
    otherTeacherToken = issueStaffToken(otherTeacherId, UserRole.TEACHER);
    linkedParentToken = issueParentToken(parentLinkedId);
    unlinkedParentToken = issueParentToken(parentUnlinkedId);
  }

  // --- mark ----------------------------------------------------------------

  @Test
  void mark_withAdmin_anyClass_returns200() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(markBody(studentLinked, classNotTaught, AttendanceStatus.PRESENT))
        .when()
        .post("/api/v1/attendance/mark")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data.status", equalTo("PRESENT"))
        .body("data.studentId", equalTo(studentLinked.toString()));
  }

  @Test
  void mark_withTeacher_ownClass_returns200() {
    given()
        .header("Authorization", "Bearer " + teacherToken)
        .contentType(ContentType.JSON)
        .body(markBody(studentLinked, classTaught, AttendanceStatus.ABSENT))
        .when()
        .post("/api/v1/attendance/mark")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data.status", equalTo("ABSENT"));
  }

  @Test
  void mark_withTeacher_classNotAssigned_returns403() {
    given()
        .header("Authorization", "Bearer " + otherTeacherToken)
        .contentType(ContentType.JSON)
        .body(markBody(studentLinked, classTaught, AttendanceStatus.ABSENT))
        .when()
        .post("/api/v1/attendance/mark")
        .then()
        .statusCode(403);
  }

  @Test
  void mark_withParent_returns403() {
    given()
        .header("Authorization", "Bearer " + linkedParentToken)
        .contentType(ContentType.JSON)
        .body(markBody(studentLinked, classTaught, AttendanceStatus.ABSENT))
        .when()
        .post("/api/v1/attendance/mark")
        .then()
        .statusCode(403);
  }

  @Test
  void mark_withoutAuth_returns401() {
    given()
        .contentType(ContentType.JSON)
        .body(markBody(studentLinked, classTaught, AttendanceStatus.PRESENT))
        .when()
        .post("/api/v1/attendance/mark")
        .then()
        .statusCode(401);
  }

  @Test
  void mark_sameStatusTwice_idempotentNoExtraOutbox() {
    mark(studentLinked, classTaught, AttendanceStatus.ABSENT, adminToken);
    long firstCount = countOutboxByEventType(AttendanceServiceImpl.EVENT_ABSENT_ALERT);
    mark(studentLinked, classTaught, AttendanceStatus.ABSENT, adminToken);
    long secondCount = countOutboxByEventType(AttendanceServiceImpl.EVENT_ABSENT_ALERT);
    assertThat(secondCount)
        .as("idempotent same-status replay must not duplicate outbox event")
        .isEqualTo(firstCount);
  }

  @Test
  void mark_presentThenAbsent_writesAbsentAlert() {
    mark(studentLinked, classTaught, AttendanceStatus.PRESENT, adminToken);
    mark(studentLinked, classTaught, AttendanceStatus.ABSENT, adminToken);
    assertThat(countOutboxByEventType(AttendanceServiceImpl.EVENT_ABSENT_ALERT))
        .as("transition to ABSENT must write attendance.absent_alert")
        .isEqualTo(1);
    assertThat(countOutboxByEventType(AttendanceServiceImpl.EVENT_MARKED))
        .as("initial PRESENT mark also writes attendance.marked")
        .isEqualTo(1);
  }

  @Test
  void mark_absentThenPresent_writesMarkedOnly() {
    mark(studentLinked, classTaught, AttendanceStatus.ABSENT, adminToken);
    mark(studentLinked, classTaught, AttendanceStatus.PRESENT, adminToken);
    assertThat(countOutboxByEventType(AttendanceServiceImpl.EVENT_ABSENT_ALERT)).isEqualTo(1);
    assertThat(countOutboxByEventType(AttendanceServiceImpl.EVENT_MARKED))
        .as("transition back to PRESENT writes attendance.marked, not an alert event")
        .isEqualTo(1);
  }

  @Test
  void mark_lateAndExcused_writeStatusSpecificEvents() {
    mark(studentLinked, classTaught, AttendanceStatus.LATE, adminToken);
    mark(studentUnlinked, classTaught, AttendanceStatus.EXCUSED, adminToken);
    assertThat(countOutboxByEventType(AttendanceServiceImpl.EVENT_LATE_ALERT)).isEqualTo(1);
    assertThat(countOutboxByEventType(AttendanceServiceImpl.EVENT_EXCUSED_ALERT)).isEqualTo(1);
    assertThat(countOutboxByEventType(AttendanceServiceImpl.EVENT_ABSENT_ALERT)).isZero();
  }

  // --- mark-all-present ----------------------------------------------------

  @Test
  void markAllPresent_skipsAlreadyPresent_writesAggregatedEvent() {
    mark(studentLinked, classTaught, AttendanceStatus.PRESENT, adminToken);
    given()
        .header("Authorization", "Bearer " + teacherToken)
        .contentType(ContentType.JSON)
        .body(bulkBody(classTaught))
        .when()
        .post("/api/v1/attendance/mark-all-present")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data.transitionedCount", equalTo(1))
        .body("data.skippedCount", equalTo(1));

    // Exactly one aggregated bulk event for the call (the prior PRESENT mark wrote a "marked"
    // event in its own transaction; we only assert on bulk_marked count here).
    List<OutboxEvent> bulkEvents =
        outboxRepository.findAll().stream()
            .filter(e -> AttendanceServiceImpl.EVENT_BULK_MARKED.equals(e.getEventType()))
            .toList();
    assertThat(bulkEvents).hasSize(1);
    JsonNode payload = readPayload(bulkEvents.get(0));
    assertThat(payload.get("transitionedCount").asInt()).isEqualTo(1);
    assertThat(payload.get("transitions").isArray()).isTrue();
    assertThat(payload.get("transitions")).hasSize(1);
  }

  @Test
  void markAllPresent_allAlreadyPresent_noBulkEvent() {
    mark(studentLinked, classTaught, AttendanceStatus.PRESENT, adminToken);
    mark(studentUnlinked, classTaught, AttendanceStatus.PRESENT, adminToken);
    given()
        .header("Authorization", "Bearer " + teacherToken)
        .contentType(ContentType.JSON)
        .body(bulkBody(classTaught))
        .when()
        .post("/api/v1/attendance/mark-all-present")
        .then()
        .statusCode(200)
        .body("data.transitionedCount", equalTo(0))
        .body("data.skippedCount", equalTo(2));
    assertThat(countOutboxByEventType(AttendanceServiceImpl.EVENT_BULK_MARKED))
        .as("no transitions means no aggregated event")
        .isZero();
  }

  // --- roster --------------------------------------------------------------

  @Test
  void roster_returnsRowPerEnrolledStudent_nullableStatus() {
    mark(studentLinked, classTaught, AttendanceStatus.ABSENT, adminToken);
    given()
        .header("Authorization", "Bearer " + teacherToken)
        .queryParam("classId", classTaught.toString())
        .queryParam("date", TODAY.toString())
        .when()
        .get("/api/v1/attendance/roster")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data.size()", equalTo(2))
        .body("data.find { it.studentId == '" + studentLinked + "' }.status", equalTo("ABSENT"))
        .body("data.find { it.studentId == '" + studentUnlinked + "' }.status", nullValue());
  }

  @Test
  void roster_withTeacher_otherClass_returns403() {
    given()
        .header("Authorization", "Bearer " + otherTeacherToken)
        .queryParam("classId", classTaught.toString())
        .queryParam("date", TODAY.toString())
        .when()
        .get("/api/v1/attendance/roster")
        .then()
        .statusCode(403);
  }

  // --- history -------------------------------------------------------------

  @Test
  void history_linkedParent_returnsOwnChildRecords() {
    mark(studentLinked, classTaught, AttendanceStatus.ABSENT, adminToken);
    given()
        .header("Authorization", "Bearer " + linkedParentToken)
        .queryParam("studentId", studentLinked.toString())
        .queryParam("from", TODAY.toString())
        .queryParam("to", TODAY.toString())
        .when()
        .get("/api/v1/attendance/history")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data.size()", greaterThanOrEqualTo(1));
  }

  @Test
  void history_unlinkedParent_returns403() {
    given()
        .header("Authorization", "Bearer " + unlinkedParentToken)
        .queryParam("studentId", studentLinked.toString())
        .queryParam("from", TODAY.toString())
        .queryParam("to", TODAY.toString())
        .when()
        .get("/api/v1/attendance/history")
        .then()
        .statusCode(403);
  }

  // --- parent-response -----------------------------------------------------

  @Test
  void parentResponse_linkedParent_returns200() {
    UUID recordId = markAndExtractId(studentLinked, classTaught, AttendanceStatus.ABSENT);
    given()
        .header("Authorization", "Bearer " + linkedParentToken)
        .contentType(ContentType.JSON)
        .body(Map.of("response", "He had a doctor's appointment."))
        .when()
        .post("/api/v1/attendance/" + recordId + "/parent-response")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data.parentResponded", equalTo(true))
        .body("data.parentRespondedAt", notNullValue());
  }

  @Test
  void parentResponse_unlinkedParent_returns404_notForbidden() {
    UUID recordId = markAndExtractId(studentLinked, classTaught, AttendanceStatus.ABSENT);
    given()
        .header("Authorization", "Bearer " + unlinkedParentToken)
        .contentType(ContentType.JSON)
        .body(Map.of("response", "shouldn't be allowed"))
        .when()
        .post("/api/v1/attendance/" + recordId + "/parent-response")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(404);
  }

  @Test
  void parentResponse_onPresentRecord_returns422() {
    UUID recordId = markAndExtractId(studentLinked, classTaught, AttendanceStatus.PRESENT);
    given()
        .header("Authorization", "Bearer " + linkedParentToken)
        .contentType(ContentType.JSON)
        .body(Map.of("response", "n/a"))
        .when()
        .post("/api/v1/attendance/" + recordId + "/parent-response")
        .then()
        .statusCode(422);
  }

  @Test
  void parentResponse_recordNotFound_returns404() {
    given()
        .header("Authorization", "Bearer " + linkedParentToken)
        .contentType(ContentType.JSON)
        .body(Map.of("response", "x"))
        .when()
        .post("/api/v1/attendance/" + UUID.randomUUID() + "/parent-response")
        .then()
        .statusCode(404);
  }

  // --- helpers -------------------------------------------------------------

  private Map<String, Object> markBody(UUID studentId, UUID classId, AttendanceStatus status) {
    Map<String, Object> body = new HashMap<>();
    body.put("studentId", studentId.toString());
    body.put("classId", classId.toString());
    body.put("date", TODAY.toString());
    body.put("status", status.name());
    return body;
  }

  private Map<String, Object> bulkBody(UUID classId) {
    Map<String, Object> body = new HashMap<>();
    body.put("classId", classId.toString());
    body.put("date", TODAY.toString());
    return body;
  }

  private void mark(UUID studentId, UUID classId, AttendanceStatus status, String token) {
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(markBody(studentId, classId, status))
        .when()
        .post("/api/v1/attendance/mark")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200);
  }

  private UUID markAndExtractId(UUID studentId, UUID classId, AttendanceStatus status) {
    String id =
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(markBody(studentId, classId, status))
            .when()
            .post("/api/v1/attendance/mark")
            .then()
            .statusCode(200)
            .extract()
            .path("data.id");
    return UUID.fromString(id);
  }

  private long countOutboxByEventType(String eventType) {
    return outboxRepository.findAll().stream()
        .filter(e -> eventType.equals(e.getEventType()))
        .count();
  }

  private JsonNode readPayload(OutboxEvent event) {
    try {
      return objectMapper.readTree(event.getPayload());
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
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

  private String issueParentToken(UUID parentId) {
    OtpService.IssuedOtp ticket = otpService.issue(parentId, schoolId);
    OtpService.ParentSession session = otpService.verify(ticket.ticketId(), ticket.code());
    return session.token();
  }
}
