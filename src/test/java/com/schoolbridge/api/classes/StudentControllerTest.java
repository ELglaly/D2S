package com.schoolbridge.api.classes;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.jwt.JwtService;
import com.schoolbridge.api.tenant.SchoolRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
class StudentControllerTest extends AbstractIntegrationTest {

  @LocalServerPort int port;
  @Autowired StudentRepository studentRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JwtService jwtService;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private String adminToken;
  private String secondAdminToken;
  private String parentToken;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    schoolId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    UUID adminId = UUID.fromString("20000000-0000-0000-0000-000000000011");
    adminToken =
        jwtService.issueAccess(
            adminId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "SCHOOL_ADMIN"));

    UUID secondAdminId = UUID.fromString("20000000-0000-0000-0000-000000000012");
    secondAdminToken =
        jwtService.issueAccess(
            secondAdminId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "SCHOOL_ADMIN"));

    UUID parentId = UUID.fromString("20000000-0000-0000-0000-000000000013");
    parentToken =
        jwtService.issueAccess(
            parentId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "PARENT"));
  }

  @Test
  void create_withAdminToken_persistsAndReturns201() {
    String studentId =
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "fullName",
                    "Ahmed Mohamed",
                    "dateOfBirth",
                    "2015-03-15",
                    "externalId",
                    "EXT-001"))
            .when()
            .post("/api/v1/students")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)
            .body("data.id", notNullValue())
            .body("data.status", equalTo("ACTIVE"))
            .extract()
            .path("data.id");
    org.assertj.core.api.Assertions.assertThat(
            studentRepository.findById(UUID.fromString(studentId)))
        .isPresent()
        .get()
        .extracting("schoolId", "fullName", "externalId", "status")
        .containsExactly(schoolId, "Ahmed Mohamed", "EXT-001", StudentStatus.ACTIVE);
  }

  @Test
  void create_withoutAuth_returns401() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("fullName", "Test", "dateOfBirth", "2015-03-15"))
        .when()
        .post("/api/v1/students")
        .then()
        .statusCode(401);
  }

  @Test
  void create_withParentRole_returns403() {
    given()
        .header("Authorization", "Bearer " + parentToken)
        .contentType(ContentType.JSON)
        .body(Map.of("fullName", "Test", "dateOfBirth", "2015-03-15"))
        .when()
        .post("/api/v1/students")
        .then()
        .statusCode(403);
  }

  @Test
  void create_missingFullName_returns422() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("dateOfBirth", "2015-03-15"))
        .when()
        .post("/api/v1/students")
        .then()
        .statusCode(422);
  }

  @Test
  void getById_notFound_returns404() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .get("/api/v1/students/" + UUID.randomUUID())
        .then()
        .statusCode(404);
  }

  @Test
  void create_duplicateExternalId_returns409() {
    Map<String, Object> body =
        Map.of("fullName", "Ahmed Mohamed", "dateOfBirth", "2015-03-15", "externalId", "DUP-001");
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/students")
        .then()
        .statusCode(201);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/students")
        .then()
        .statusCode(409);
  }

  @Test
  void bulkImport_usesSlashActionRoute() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .multiPart(
            "file",
            "students.csv",
            new ByteArrayInputStream(
                "externalId,fullName,dateOfBirth,className\nEXT-001,Ahmed,2015-03-15,\n"
                    .getBytes(StandardCharsets.UTF_8)),
            "text/csv")
        .when()
        .post("/api/v1/students/bulk-import")
        .then()
        .statusCode(200)
        .body("data.importedCount", equalTo(1));
  }

  @Test
  void idempotencyKey_isScopedToTheAuthenticatedUser() {
    String idempotencyKey = UUID.randomUUID().toString();
    Map<String, Object> first =
        Map.of(
            "fullName", "First Admin Student", "dateOfBirth", "2015-03-15", "externalId", "IDEM-1");
    Map<String, Object> second =
        Map.of(
            "fullName",
            "Second Admin Student",
            "dateOfBirth",
            "2015-03-15",
            "externalId",
            "IDEM-2");

    given()
        .header("Authorization", "Bearer " + adminToken)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(ContentType.JSON)
        .body(first)
        .when()
        .post("/api/v1/students")
        .then()
        .statusCode(201)
        .header("Idempotent-Replay", org.hamcrest.Matchers.nullValue());

    given()
        .header("Authorization", "Bearer " + secondAdminToken)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(ContentType.JSON)
        .body(second)
        .when()
        .post("/api/v1/students")
        .then()
        .statusCode(201)
        .header("Idempotent-Replay", org.hamcrest.Matchers.nullValue())
        .body("data.externalId", equalTo("IDEM-2"));

    given()
        .header("Authorization", "Bearer " + adminToken)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(ContentType.JSON)
        .body(first)
        .when()
        .post("/api/v1/students")
        .then()
        .statusCode(201)
        .header("Idempotent-Replay", equalTo("true"))
        .body("data.externalId", equalTo("IDEM-1"));
  }
}
