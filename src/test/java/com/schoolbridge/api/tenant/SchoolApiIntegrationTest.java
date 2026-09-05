package com.schoolbridge.api.tenant;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.SqlIntegrationTest;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SchoolApiIntegrationTest extends SqlIntegrationTest {

  @Autowired JdbcTemplate jdbc;

  private String superAdminToken;

  @BeforeEach
  void setUp() {
    // Mint a fresh SUPER_ADMIN JWT for each test. The signing key is ephemeral per JVM, so a
    // token issued here is verifiable by the same JwtService bean a moment later.
    superAdminToken = login("admin@platform.test", "password");
    // Delete in dependency order — child rows first — so FK constraints don't bite when prior
    // test classes leave users behind.
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

    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_events where event_type = 'school.created' and aggregate_type = 'School' and aggregate_id = ?",
                Long.class,
                UUID.fromString(id)))
        .isEqualTo(1L);
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

  @Test
  void listSettingsSuspendAndReactivatePersistThroughHttp() {
    String schoolId = "10000000-0000-0000-0000-000000000001";
    given()
        .header("Authorization", "Bearer " + superAdminToken)
        .get("/api/v1/schools?status=ACTIVE")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + superAdminToken)
        .get("/api/v1/schools/{id}/settings", schoolId)
        .then()
        .statusCode(200)
        .body("data.defaultLanguage", equalTo("EN"));

    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + superAdminToken)
        .body(settingsPayload())
        .put("/api/v1/schools/{id}/settings", schoolId)
        .then()
        .statusCode(200)
        .body("data.quietHoursStart", equalTo("22:00:00"));
    assertThat(
            jdbc.queryForObject(
                "select quiet_hours_start::text from schools where id = ?",
                String.class,
                UUID.fromString(schoolId)))
        .isEqualTo("20:00:00");

    given()
        .header("Authorization", "Bearer " + superAdminToken)
        .post("/api/v1/schools/{id}/suspend", schoolId)
        .then()
        .statusCode(204);
    assertThat(
            jdbc.queryForObject(
                "select status from schools where id = ?", String.class, UUID.fromString(schoolId)))
        .isEqualTo("SUSPENDED");
    given()
        .header("Authorization", "Bearer " + superAdminToken)
        .post("/api/v1/schools/{id}/reactivate", schoolId)
        .then()
        .statusCode(204);
    assertThat(
            jdbc.queryForObject(
                "select status from schools where id = ?", String.class, UUID.fromString(schoolId)))
        .isEqualTo("ACTIVE");
  }

  @Test
  void schoolAdminIsDeniedPlatformSchoolManagement() {
    String schoolAdmin = login("school-admin@fixture.test", "password");
    authenticated(schoolAdmin).get("/api/v1/schools").then().statusCode(403);
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

  private static Map<String, Object> settingsPayload() {
    return Map.of(
        "defaultLanguage", "EN",
        "quietHoursStart", "22:00:00",
        "quietHoursEnd", "06:00:00",
        "homeworkReminderEnabled", true,
        "homeworkReminderTime", "19:00:00",
        "feeReminderOffsetDays", List.of(-7, -1, 0, 7),
        "smsFallbackEnabled", false,
        "alertsRespectQuietHours", false,
        "rosterDueByLocalTime", "09:00:00");
  }
}
