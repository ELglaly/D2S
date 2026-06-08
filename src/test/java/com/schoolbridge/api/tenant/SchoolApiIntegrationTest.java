package com.schoolbridge.api.tenant;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.common.outbox.OutboxEvent;
import com.schoolbridge.api.common.outbox.OutboxRepository;
import com.schoolbridge.api.common.outbox.OutboxStatus;
import com.schoolbridge.api.identity.PlatformAdminRepository;
import com.schoolbridge.api.identity.RefreshTokenRepository;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.jwt.JwtService;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchoolApiIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort int port;
  @Autowired OutboxRepository outboxRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired UserRepository userRepository;
  @Autowired RefreshTokenRepository refreshTokenRepository;
  @Autowired PlatformAdminRepository platformAdminRepository;
  @Autowired JwtService jwtService;

  private String superAdminToken;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    // Mint a fresh SUPER_ADMIN JWT for each test. The signing key is ephemeral per JVM, so a
    // token issued here is verifiable by the same JwtService bean a moment later.
    superAdminToken =
        jwtService.issueAccess(
            UUID.randomUUID().toString(), Map.of("kind", "PLATFORM_ADMIN", "role", "SUPER_ADMIN"));
    // Delete in dependency order — child rows first — so FK constraints don't bite when prior
    // test classes leave users behind.
    refreshTokenRepository.deleteAll();
    userRepository.deleteAll();
    platformAdminRepository.deleteAll();
    schoolRepository.deleteAll();
    outboxRepository.deleteAll();
  }

  @Test
  void create_withoutToken_returns401() {
    given()
        .contentType(ContentType.JSON)
        .body(validCreatePayload("School A"))
        .when()
        .post("/api/v1/schools")
        .then()
        .statusCode(401);
  }

  @Test
  void create_withBadBearer_returns401() {
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer not-a-real-token")
        .body(validCreatePayload("School B"))
        .when()
        .post("/api/v1/schools")
        .then()
        .statusCode(401);
  }

  @Test
  void create_validPayload_returns201AndWritesOutbox() {
    String id =
        given()
            .contentType(ContentType.JSON)
            .header("Authorization", "Bearer " + superAdminToken)
            .body(validCreatePayload("School C"))
            .when()
            .post("/api/v1/schools")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)
            .body("data.id", notNullValue())
            .body("data.status", equalTo("ACTIVE"))
            .body("data.settings.defaultLanguage", equalTo("EN"))
            .extract()
            .path("data.id");

    List<OutboxEvent> events =
        outboxRepository.findByStatusOrderByCreatedAtAsc(
            OutboxStatus.PENDING, PageRequest.of(0, 10));
    assertThat(events)
        .extracting(OutboxEvent::getEventType, OutboxEvent::getAggregateType)
        .containsExactly(tuple("school.created", "School"));
    assertThat(events.get(0).getAggregateId()).isEqualTo(UUID.fromString(id));
  }

  @Test
  void create_invalidPayload_returns422WithFieldErrors() {
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + superAdminToken)
        .body(Map.of("name", ""))
        .when()
        .post("/api/v1/schools")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(422)
        .body("type", equalTo("https://schoolbridge.app/errors/validation"))
        .body("errors", notNullValue());
  }

  @Test
  void get_unknownId_returns404() {
    given()
        .header("Authorization", "Bearer " + superAdminToken)
        .when()
        .get("/api/v1/schools/" + UUID.randomUUID())
        .then()
        .statusCode(404)
        .body("type", equalTo("https://schoolbridge.app/errors/not-found"));
  }

  @Test
  void suspendTwice_returns204Then409() {
    String id = createSchool("School D");

    given()
        .header("Authorization", "Bearer " + superAdminToken)
        .when()
        .post("/api/v1/schools/" + id + "/suspend")
        .then()
        .statusCode(204);

    given()
        .header("Authorization", "Bearer " + superAdminToken)
        .when()
        .post("/api/v1/schools/" + id + "/suspend")
        .then()
        .statusCode(409)
        .body("type", equalTo("https://schoolbridge.app/errors/conflict"));
  }

  @Test
  void idempotencyKeyReplayedRequestReturnsCachedResponse() {
    String idemKey = UUID.randomUUID().toString();
    var first =
        given()
            .contentType(ContentType.JSON)
            .header("Authorization", "Bearer " + superAdminToken)
            .header("Idempotency-Key", idemKey)
            .body(validCreatePayload("School E"))
            .when()
            .post("/api/v1/schools");
    first.then().statusCode(201);
    String firstId = first.jsonPath().getString("data.id");

    var replay =
        given()
            .contentType(ContentType.JSON)
            .header("Authorization", "Bearer " + superAdminToken)
            .header("Idempotency-Key", idemKey)
            .body(validCreatePayload("School E"))
            .when()
            .post("/api/v1/schools");
    replay
        .then()
        .statusCode(201)
        .header("Idempotent-Replay", equalTo("true"))
        .body("data.id", equalTo(firstId));
  }

  private String createSchool(String name) {
    return given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + superAdminToken)
        .body(validCreatePayload(name))
        .when()
        .post("/api/v1/schools")
        .then()
        .statusCode(201)
        .extract()
        .path("data.id");
  }

  private static Map<String, Object> validCreatePayload(String name) {
    return Map.of(
        "name", name,
        "country", "EG",
        "timezone", "Africa/Cairo",
        "locale", "ar-EG",
        "subscriptionTier", "STANDARD");
  }
}
