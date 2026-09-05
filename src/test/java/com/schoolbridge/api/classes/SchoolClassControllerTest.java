package com.schoolbridge.api.classes;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
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
      "classpath:sql/fixtures/identity/auth-principals.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
    scripts = "classpath:sql/cleanup/all-data.sql",
    executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class SchoolClassControllerTest extends AbstractIntegrationTest {

  @LocalServerPort int port;
  @Autowired SchoolClassRepository classRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JwtService jwtService;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private String adminToken;
  private String teacherToken;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    schoolId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    UUID adminId = UUID.fromString("20000000-0000-0000-0000-000000000011");
    adminToken =
        jwtService.issueAccess(
            adminId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "SCHOOL_ADMIN"));
    UUID teacherId = UUID.fromString("20000000-0000-0000-0000-000000000010");
    teacherToken =
        jwtService.issueAccess(
            teacherId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "TEACHER"));
  }

  @Test
  void create_withAdminToken_persistsAndReturns201() {
    String createdId =
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(Map.of("name", "Grade 3A", "gradeLevel", "Grade 3", "academicYear", "2025-2026"))
            .when()
            .post("/api/v1/classes")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)
            .body("data.id", notNullValue())
            .body("data.name", equalTo("Grade 3A"))
            .extract()
            .path("data.id");
    org.assertj.core.api.Assertions.assertThat(classRepository.findById(UUID.fromString(createdId)))
        .isPresent()
        .get()
        .extracting("schoolId", "name", "gradeLevel", "academicYear")
        .containsExactly(schoolId, "Grade 3A", "Grade 3", "2025-2026");
  }

  @Test
  void create_withoutAuth_returns401() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "Grade 3A", "gradeLevel", "Grade 3", "academicYear", "2025-2026"))
        .when()
        .post("/api/v1/classes")
        .then()
        .statusCode(401);
  }

  @Test
  void create_withTeacherToken_returns403() {
    given()
        .header("Authorization", "Bearer " + teacherToken)
        .contentType(ContentType.JSON)
        .body(Map.of("name", "Grade 3A", "gradeLevel", "Grade 3", "academicYear", "2025-2026"))
        .when()
        .post("/api/v1/classes")
        .then()
        .statusCode(403);
  }

  @Test
  void create_missingName_returns422() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("gradeLevel", "Grade 3", "academicYear", "2025-2026"))
        .when()
        .post("/api/v1/classes")
        .then()
        .statusCode(422);
  }

  @Test
  void getById_notFound_returns404() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .get("/api/v1/classes/" + UUID.randomUUID())
        .then()
        .statusCode(404);
  }

  @Test
  void updateAndDelete_fullCycle_succeeds() {
    // Create
    UUID classId =
        UUID.fromString(
            given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(
                    Map.of(
                        "name", "Grade 3A",
                        "gradeLevel", "Grade 3",
                        "academicYear", "2025-2026"))
                .when()
                .post("/api/v1/classes")
                .then()
                .statusCode(201)
                .extract()
                .path("data.id"));

    // Update
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name", "Grade 3B",
                "gradeLevel", "Grade 3",
                "academicYear", "2025-2026"))
        .when()
        .patch("/api/v1/classes/" + classId)
        .then()
        .statusCode(200)
        .body("data.name", equalTo("Grade 3B"));

    // Delete
    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .delete("/api/v1/classes/" + classId)
        .then()
        .statusCode(204);

    org.assertj.core.api.Assertions.assertThat(classRepository.existsById(classId)).isFalse();

    // Verify gone
    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .get("/api/v1/classes/" + classId)
        .then()
        .statusCode(404);
  }

  @Test
  void list_withTeacherToken_returns403() {
    // Class listing is an admin-management read (CLASS_MANAGE); teachers use /my-classes instead.
    given()
        .header("Authorization", "Bearer " + teacherToken)
        .when()
        .get("/api/v1/classes")
        .then()
        .statusCode(403);
  }
}
