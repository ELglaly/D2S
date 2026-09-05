package com.schoolbridge.api.classes;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.SqlIntegrationTest;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
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
      "classpath:sql/fixtures/classes/academic-roster.sql",
      "classpath:sql/fixtures/classes/parent-children.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
    scripts = "classpath:sql/cleanup/all-data.sql",
    executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ParentStudentLinkControllerTest extends SqlIntegrationTest {

  @LocalServerPort int port;
  @Autowired ParentStudentLinkRepository linkRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired UserRepository userRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JwtService jwtService;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID studentId;
  private UUID parentId;
  private String adminToken;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    schoolId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    studentId = UUID.fromString("30000000-0000-0000-0000-000000000011");
    parentId = UUID.fromString("20000000-0000-0000-0000-000000000014");
    adminToken = login("school-admin@fixture.test", "password");
  }

  @Test
  void create_validRequest_returns201() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "parentUserId",
                parentId.toString(),
                "studentId",
                studentId.toString(),
                "relationship",
                "MOTHER",
                "primaryContact",
                true))
        .when()
        .post("/api/v1/parent-links")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .body("data.id", notNullValue())
        .body("data.relationship", equalTo("MOTHER"))
        .body("data.primaryContact", equalTo(true));
  }

  @Test
  void create_withoutAuth_returns401() {
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "parentUserId",
                parentId.toString(),
                "studentId",
                studentId.toString(),
                "relationship",
                "MOTHER",
                "primaryContact",
                false))
        .when()
        .post("/api/v1/parent-links")
        .then()
        .statusCode(401);
  }

  @Test
  void create_missingStudentId_returns422() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "parentUserId",
                parentId.toString(),
                "relationship",
                "MOTHER",
                "primaryContact",
                false))
        .when()
        .post("/api/v1/parent-links")
        .then()
        .statusCode(422);
  }

  @Test
  void create_duplicate_returns409() {
    Map<String, Object> body =
        Map.of(
            "parentUserId",
            parentId.toString(),
            "studentId",
            studentId.toString(),
            "relationship",
            "MOTHER",
            "primaryContact",
            true);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/parent-links")
        .then()
        .statusCode(201);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/parent-links")
        .then()
        .statusCode(409);
  }

  @Test
  void delete_existing_returns204() {
    String linkId =
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "parentUserId",
                    parentId.toString(),
                    "studentId",
                    studentId.toString(),
                    "relationship",
                    "FATHER",
                    "primaryContact",
                    false))
            .when()
            .post("/api/v1/parent-links")
            .then()
            .statusCode(201)
            .extract()
            .path("data.id");

    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .delete("/api/v1/parent-links/" + linkId)
        .then()
        .statusCode(204);
  }
}
