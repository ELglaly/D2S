package com.schoolbridge.api.announcements;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.jwt.JwtService;
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
 * REST-layer test for {@link AnnouncementController}: 201/200/403/404/409/422 matrix per endpoint.
 * Fan-out semantics and outbox payload are covered by {@code AnnouncementFanoutIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnnouncementControllerTest extends AbstractIntegrationTest {

  @LocalServerPort int port;

  @Autowired AnnouncementRepository announcementRepository;
  @Autowired AnnouncementRecipientRepository recipientRepository;
  @Autowired TeacherSubjectAssignmentRepository teacherSubjectAssignmentRepository;
  @Autowired ClassSubjectRepository classSubjectRepository;
  @Autowired SubjectRepository subjectRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JwtService jwtService;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID classIdTaughtByTeacher;
  private UUID otherClassId;
  private String adminToken;
  private String teacherToken;
  private String otherTeacherToken;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    tx.executeWithoutResult(s -> recipientRepository.deleteAll());
    tx.executeWithoutResult(s -> announcementRepository.deleteAll());
    tx.executeWithoutResult(s -> teacherSubjectAssignmentRepository.deleteAll());
    tx.executeWithoutResult(s -> classSubjectRepository.deleteAll());
    tx.executeWithoutResult(s -> subjectRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Ctrl Test School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());

    UUID adminId =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.staff(
                            schoolId,
                            UserRole.SCHOOL_ADMIN,
                            "Admin",
                            "admin@ann-ctrl.test",
                            passwordEncoder.encode("pass")))
                    .getId());
    adminToken =
        jwtService.issueAccess(
            adminId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "SCHOOL_ADMIN"));

    UUID teacherId =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.staff(
                            schoolId,
                            UserRole.TEACHER,
                            "Teacher",
                            "teacher@ann-ctrl.test",
                            passwordEncoder.encode("pass")))
                    .getId());
    teacherToken =
        jwtService.issueAccess(
            teacherId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "TEACHER"));

    UUID otherTeacherId =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.staff(
                            schoolId,
                            UserRole.TEACHER,
                            "Other Teacher",
                            "other-teacher@ann-ctrl.test",
                            passwordEncoder.encode("pass")))
                    .getId());
    otherTeacherToken =
        jwtService.issueAccess(
            otherTeacherId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "TEACHER"));

    classIdTaughtByTeacher =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolId, "Grade 3A", "Grade 3", "2025-2026", null))
                    .getId());
    otherClassId =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolId, "Grade 4A", "Grade 4", "2025-2026", null))
                    .getId());

    UUID mathSubjectId =
        tx.execute(
            s -> subjectRepository.save(new Subject(schoolId, "Mathematics", null, null)).getId());
    tx.executeWithoutResult(
        s ->
            classSubjectRepository.save(
                new ClassSubject(schoolId, classIdTaughtByTeacher, mathSubjectId)));
    tx.executeWithoutResult(
        s ->
            teacherSubjectAssignmentRepository.save(
                new TeacherSubjectAssignment(
                    schoolId, teacherId, classIdTaughtByTeacher, mathSubjectId)));
  }

  private Map<String, Object> baseSchoolBody() {
    Map<String, Object> body = new HashMap<>();
    body.put("scopeType", "SCHOOL");
    body.put("language", "AR");
    body.put("body", "إعلان عام لجميع أولياء الأمور");
    body.put("requiresAck", false);
    return body;
  }

  @Test
  void create_withAdmin_schoolScope_returns201() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(baseSchoolBody())
        .when()
        .post("/api/v1/announcements")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .body("data.id", notNullValue())
        .body("data.scopeType", equalTo("SCHOOL"))
        .body("data.status", equalTo("SENT"));
  }

  @Test
  void create_withoutAuth_returns401() {
    given()
        .contentType(ContentType.JSON)
        .body(baseSchoolBody())
        .when()
        .post("/api/v1/announcements")
        .then()
        .statusCode(401);
  }

  @Test
  void create_withTeacher_schoolScope_returns403() {
    given()
        .header("Authorization", "Bearer " + teacherToken)
        .contentType(ContentType.JSON)
        .body(baseSchoolBody())
        .when()
        .post("/api/v1/announcements")
        .then()
        .statusCode(403);
  }

  @Test
  void create_withTeacher_ownClassScope_returns201() {
    Map<String, Object> body = new HashMap<>();
    body.put("scopeType", "CLASS");
    body.put("classId", classIdTaughtByTeacher.toString());
    body.put("language", "AR");
    body.put("body", "إعلان للصف");
    body.put("requiresAck", false);

    given()
        .header("Authorization", "Bearer " + teacherToken)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/announcements")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .body("data.scopeType", equalTo("CLASS"))
        .body("data.scopeValue", equalTo(classIdTaughtByTeacher.toString()));
  }

  @Test
  void create_withTeacher_classNotAssigned_returns403() {
    Map<String, Object> body = new HashMap<>();
    body.put("scopeType", "CLASS");
    body.put("classId", otherClassId.toString());
    body.put("language", "AR");
    body.put("body", "إعلان لصف لا أُدرّسه");
    body.put("requiresAck", false);

    given()
        .header("Authorization", "Bearer " + otherTeacherToken)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/announcements")
        .then()
        .statusCode(403);
  }

  @Test
  void create_missingBody_returns422() {
    Map<String, Object> body = new HashMap<>();
    body.put("scopeType", "SCHOOL");
    body.put("language", "AR");
    body.put("requiresAck", false);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/announcements")
        .then()
        .statusCode(422);
  }

  @Test
  void create_classScopeWithoutClassId_returns422() {
    Map<String, Object> body = new HashMap<>();
    body.put("scopeType", "CLASS");
    body.put("language", "AR");
    body.put("body", "ينقصها معرّف الصف");
    body.put("requiresAck", false);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/announcements")
        .then()
        .statusCode(422);
  }

  @Test
  void create_customScopeWithEmptyList_returns422() {
    Map<String, Object> body = new HashMap<>();
    body.put("scopeType", "CUSTOM");
    body.put("recipientStudentIds", List.of());
    body.put("language", "AR");
    body.put("body", "قائمة فارغة");
    body.put("requiresAck", false);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/announcements")
        .then()
        .statusCode(422);
  }

  @Test
  void get_notFound_returns404() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .get("/api/v1/announcements/" + UUID.randomUUID())
        .then()
        .statusCode(404);
  }

  @Test
  void recall_existing_returns200() {
    String id =
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(baseSchoolBody())
            .when()
            .post("/api/v1/announcements")
            .then()
            .statusCode(201)
            .extract()
            .path("data.id");

    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .post("/api/v1/announcements/" + id + "/recall")
        .then()
        .statusCode(200)
        .body("data.status", equalTo("RECALLED"));
  }

  @Test
  void recall_twice_returns409() {
    String id =
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(baseSchoolBody())
            .when()
            .post("/api/v1/announcements")
            .then()
            .extract()
            .path("data.id");

    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .post("/api/v1/announcements/" + id + "/recall")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .post("/api/v1/announcements/" + id + "/recall")
        .then()
        .statusCode(409);
  }

  @Test
  void listRecipients_existing_returns200() {
    String id =
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(baseSchoolBody())
            .when()
            .post("/api/v1/announcements")
            .then()
            .extract()
            .path("data.id");

    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .get("/api/v1/announcements/" + id + "/recipients")
        .then()
        .statusCode(200)
        .body("data", notNullValue());
  }
}
