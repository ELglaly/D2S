package com.schoolbridge.api.notifications;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import com.schoolbridge.api.SqlIntegrationTest;
import io.restassured.http.ContentType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class NotificationPreferenceControllerTest extends SqlIntegrationTest {

  @Autowired JdbcTemplate jdbc;
  private String token;

  @BeforeEach
  void setUp() {
    token = login("school-admin@fixture.test", "password");
  }

  @Test
  void get_withNoStoredRows_materialisesEveryCategoryWithDefaults() {
    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get("/api/v1/notifications/preferences")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        // Every category, not just the configured ones — a missing entry would read as "off".
        .body("data.preferences", hasSize(3))
        .body("data.preferences.category", hasItem("ATTENDANCE"))
        .body("data.quietHoursStart", nullValue())
        // The school's window is still reported, so the client can render it without a second call.
        .body("data.effectiveQuietHoursStart", equalTo("23:00:00"))
        .body("data.respectQuietHours", equalTo(false));
  }

  @Test
  void put_roundTripsAndIsReflectedInTheNextGet() {
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(body(true, "22:00:00", "06:30:00", categoryPreference("HOMEWORK", false)))
        .when()
        .put("/api/v1/notifications/preferences")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data.respectQuietHours", equalTo(true))
        .body("data.effectiveQuietHoursStart", equalTo("22:00:00"));

    assertThat(jdbc.queryForObject("select count(*) from notification_preferences", Long.class))
        .isEqualTo(1L);

    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get("/api/v1/notifications/preferences")
        .then()
        .statusCode(200)
        .body("data.quietHoursEnd", equalTo("06:30:00"))
        .body("data.preferences.find { it.category == 'HOMEWORK' }.enabled", equalTo(false));
  }

  @Test
  void put_disablingAttendance_isRejected() {
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(body(true, null, null, categoryPreference("ATTENDANCE", false)))
        .when()
        .put("/api/v1/notifications/preferences")
        .then()
        .statusCode(422);
  }

  @Test
  void put_halfAQuietHoursWindow_isRejected() {
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(body(true, "22:00:00", null, categoryPreference("HOMEWORK", true)))
        .when()
        .put("/api/v1/notifications/preferences")
        .then()
        .statusCode(422);
  }

  @Test
  void put_emptyChannelList_isRejected() {
    Map<String, Object> preference = new HashMap<>();
    preference.put("category", "HOMEWORK");
    preference.put("enabled", true);
    preference.put("channels", List.of());

    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(body(false, null, null, preference))
        .when()
        .put("/api/v1/notifications/preferences")
        .then()
        .statusCode(422);
  }

  @Test
  void get_withoutAuth_returns401() {
    given().when().get("/api/v1/notifications/preferences").then().statusCode(401);
  }

  private static Map<String, Object> categoryPreference(String category, boolean enabled) {
    Map<String, Object> preference = new HashMap<>();
    preference.put("category", category);
    preference.put("enabled", enabled);
    preference.put("channels", List.of("PUSH", "WHATSAPP"));
    return preference;
  }

  private static Map<String, Object> body(
      boolean respect, String start, String end, Map<String, Object> preference) {
    // HashMap, not Map.of — the quiet-hours pair is deliberately null in half these cases.
    Map<String, Object> body = new HashMap<>();
    body.put("respectQuietHours", respect);
    body.put("quietHoursStart", start);
    body.put("quietHoursEnd", end);
    body.put("preferences", List.of(preference));
    return body;
  }
}
