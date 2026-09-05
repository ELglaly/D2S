package com.schoolbridge.api.classes;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.jwt.JwtService;
import com.schoolbridge.api.tenant.SchoolRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql(
    scripts = {
      "classpath:sql/cleanup/all-data.sql",
      "classpath:sql/fixtures/common/schools.sql",
      "classpath:sql/fixtures/identity/auth-principals.sql",
      "classpath:sql/fixtures/classes/academic-roster.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
    scripts = "classpath:sql/cleanup/all-data.sql",
    executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
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
    schoolId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    classId = UUID.fromString("30000000-0000-0000-0000-000000000001");
    studentId = UUID.fromString("30000000-0000-0000-0000-000000000011");
    UUID adminId = UUID.fromString("20000000-0000-0000-0000-000000000011");
    adminToken =
        jwtService.issueAccess(
            adminId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "SCHOOL_ADMIN"));
  }

  @Test
  void enroll_validRequest_persistsAndReturns201() {
    String enrollmentId =
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
            .body("data.studentId", notNullValue())
            .extract()
            .path("data.id");
    org.assertj.core.api.Assertions.assertThat(
            enrollmentRepository.findById(UUID.fromString(enrollmentId)))
        .isPresent()
        .get()
        .extracting("schoolId", "studentId", "classId")
        .containsExactly(schoolId, studentId, classId);
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
    org.assertj.core.api.Assertions.assertThat(
            enrollmentRepository.existsById(UUID.fromString(enrollmentId)))
        .isFalse();
  }
}
