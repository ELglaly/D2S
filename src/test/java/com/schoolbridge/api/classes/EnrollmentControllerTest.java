package com.schoolbridge.api.classes;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.jwt.JwtService;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EnrollmentControllerTest extends AbstractIntegrationTest {

  @LocalServerPort int port;
  @Autowired EnrollmentRepository enrollmentRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JwtService jwtService;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID classId;
  private UUID studentId;
  private String adminToken;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    tx.executeWithoutResult(s -> enrollmentRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Test School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());

    classId =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolId, "Grade 3A", "Grade 3", "2025-2026", null))
                    .getId());

    studentId =
        tx.execute(
            s ->
                studentRepository
                    .save(
                        new Student(
                            schoolId, "Ahmed Mohamed", LocalDate.of(2015, 3, 15), "EXT-001"))
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
                            "admin@enroll.test",
                            passwordEncoder.encode("pass")))
                    .getId());
    adminToken =
        jwtService.issueAccess(
            adminId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "SCHOOL_ADMIN"));
  }

  @Test
  void enroll_validRequest_returns201() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("studentId", studentId.toString()))
        .when()
        .post("/api/v1/classes/" + classId + "/enrollments")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .body("data.studentId", notNullValue());
  }

  @Test
  void enroll_withoutAuth_returns401() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("studentId", studentId.toString()))
        .when()
        .post("/api/v1/classes/" + classId + "/enrollments")
        .then()
        .statusCode(401);
  }

  @Test
  void enroll_missingStudentId_returns422() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/api/v1/classes/" + classId + "/enrollments")
        .then()
        .statusCode(422);
  }

  @Test
  void enroll_duplicate_returns409() {
    // First enroll
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("studentId", studentId.toString()))
        .when()
        .post("/api/v1/classes/" + classId + "/enrollments")
        .then()
        .statusCode(201);

    // Duplicate
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("studentId", studentId.toString()))
        .when()
        .post("/api/v1/classes/" + classId + "/enrollments")
        .then()
        .statusCode(409);
  }

  @Test
  void deleteEnrollment_returns204() {
    String enrollmentId =
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(Map.of("studentId", studentId.toString()))
            .when()
            .post("/api/v1/classes/" + classId + "/enrollments")
            .then()
            .statusCode(201)
            .extract()
            .path("data.id");

    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .delete("/api/v1/enrollments/" + enrollmentId)
        .then()
        .statusCode(204);
  }
}
