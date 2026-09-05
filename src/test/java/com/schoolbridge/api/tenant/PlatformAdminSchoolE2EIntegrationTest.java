package com.schoolbridge.api.tenant;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.SqlIntegrationTest;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Platform-admin school lifecycle using only credentials and resources issued by HTTP APIs. */
class PlatformAdminSchoolE2EIntegrationTest extends SqlIntegrationTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void provisionConfigureSuspendDenyAndReactivateTenant() {
    String platform = login("admin@platform.test", "password");
    String schoolId =
        authenticated(platform)
            .contentType(ContentType.JSON)
            .body(createPayload())
            .post("/api/v1/schools")
            .then()
            .statusCode(201)
            .extract()
            .path("data.id");

    authenticated(platform)
        .contentType(ContentType.JSON)
        .body(settingsPayload())
        .put("/api/v1/schools/{id}/settings", schoolId)
        .then()
        .statusCode(200);
    authenticated(platform)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "role", "SCHOOL_ADMIN",
                "name", "Journey Admin",
                "email", "journey.admin@fixture.test",
                "password", "password1"))
        .post("/api/v1/schools/{id}/users", schoolId)
        .then()
        .statusCode(201);

    login("journey.admin@fixture.test", "password1");
    authenticated(platform).post("/api/v1/schools/{id}/suspend", schoolId).then().statusCode(204);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("email", "journey.admin@fixture.test", "password", "password1"))
        .post("/api/v1/auth/login")
        .then()
        .statusCode(401);
    authenticated(platform)
        .post("/api/v1/schools/{id}/reactivate", schoolId)
        .then()
        .statusCode(204);
    login("journey.admin@fixture.test", "password1");

    assertThat(
            jdbc.queryForObject(
                "select status from schools where id = ?",
                String.class,
                java.util.UUID.fromString(schoolId)))
        .isEqualTo("ACTIVE");
  }

  private static Map<String, Object> createPayload() {
    return Map.of(
        "name", "Journey School",
        "country", "EG",
        "timezone", "Africa/Cairo",
        "locale", "en-EG",
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
