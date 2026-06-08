package com.schoolbridge.api.classes;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.AbstractIntegrationTest;
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
  private String parentToken;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
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

    UUID adminId =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.staff(
                            schoolId,
                            UserRole.SCHOOL_ADMIN,
                            "Admin User",
                            "admin@student.test",
                            passwordEncoder.encode("pass")))
                    .getId());
    adminToken =
        jwtService.issueAccess(
            adminId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "SCHOOL_ADMIN"));

    UUID parentId =
        tx.execute(
            s ->
                userRepository
                    .save(User.parent(schoolId, "Parent User", "+201009990001", "hash001"))
                    .getId());
    parentToken =
        jwtService.issueAccess(
            parentId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "PARENT"));
  }

  @Test
  void create_withAdminToken_returns201() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "fullName", "Ahmed Mohamed", "dateOfBirth", "2015-03-15", "externalId", "EXT-001"))
        .when()
        .post("/api/v1/students")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .body("data.id", notNullValue())
        .body("data.status", equalTo("ACTIVE"));
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
}
