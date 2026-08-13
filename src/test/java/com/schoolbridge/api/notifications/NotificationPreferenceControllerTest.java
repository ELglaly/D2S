package com.schoolbridge.api.notifications;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import com.schoolbridge.api.AbstractIntegrationTest;
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
import java.util.HashMap;
import java.util.List;
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
class NotificationPreferenceControllerTest extends AbstractIntegrationTest {

  @LocalServerPort int port;

  @Autowired NotificationPreferenceRepository preferenceRepository;
  @Autowired NotificationSettingsRepository settingsRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JwtService jwtService;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private String token;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    tx.executeWithoutResult(s -> preferenceRepository.deleteAll());
    tx.executeWithoutResult(s -> settingsRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Prefs Test School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());
    UUID userId =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.staff(
                            schoolId,
                            UserRole.SCHOOL_ADMIN,
                            "Admin",
                            "admin@prefs.test",
                            passwordEncoder.encode("pass")))
                    .getId());
    token =
        jwtService.issueAccess(
            userId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "SCHOOL_ADMIN"));
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
        .body("data.effectiveQuietHoursStart", equalTo("21:00:00"))
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
