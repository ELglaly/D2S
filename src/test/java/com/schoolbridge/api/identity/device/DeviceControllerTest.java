package com.schoolbridge.api.identity.device;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

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
class DeviceControllerTest extends AbstractIntegrationTest {

  @LocalServerPort int port;

  @Autowired DeviceTokenRepository deviceTokenRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JwtService jwtService;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID adminId;
  private String adminToken;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    tx.executeWithoutResult(s -> deviceTokenRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Device Test School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());

    adminId =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.staff(
                            schoolId,
                            UserRole.SCHOOL_ADMIN,
                            "Admin",
                            "admin@device.test",
                            passwordEncoder.encode("pass")))
                    .getId());
    adminToken =
        jwtService.issueAccess(
            adminId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "SCHOOL_ADMIN"));
  }

  @Test
  void register_validRequest_returns200WithDeviceInfo() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("platform", "ANDROID", "fcmToken", "fcm-abc-123", "deviceId", "dev-001"))
        .when()
        .post("/api/v1/devices/register")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data.id", notNullValue())
        .body("data.platform", equalTo("ANDROID"))
        .body("data.deviceId", equalTo("dev-001"))
        .body("data.active", equalTo(true));
  }

  @Test
  void register_sameDevice_updatesToken() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("platform", "ANDROID", "fcmToken", "fcm-old-token", "deviceId", "dev-upsert"))
        .when()
        .post("/api/v1/devices/register")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("platform", "ANDROID", "fcmToken", "fcm-new-token", "deviceId", "dev-upsert"))
        .when()
        .post("/api/v1/devices/register")
        .then()
        .statusCode(200);

    // Exactly one row: upsert, not insert
    long count =
        tx.execute(
            s ->
                deviceTokenRepository.findAll().stream()
                    .filter(d -> "dev-upsert".equals(d.getDeviceId()))
                    .count());
    assertThat(count).isEqualTo(1L);
  }

  @Test
  void register_withoutAuth_returns401() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("platform", "ANDROID", "fcmToken", "tok", "deviceId", "dev-x"))
        .when()
        .post("/api/v1/devices/register")
        .then()
        .statusCode(401);
  }

  @Test
  void register_missingFcmToken_returns422() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("platform", "IOS", "deviceId", "dev-y"))
        .when()
        .post("/api/v1/devices/register")
        .then()
        .statusCode(422);
  }

  @Test
  void deregister_existing_returns204() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("platform", "IOS", "fcmToken", "fcm-del", "deviceId", "dev-to-delete"))
        .when()
        .post("/api/v1/devices/register")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .delete("/api/v1/devices/dev-to-delete")
        .then()
        .statusCode(204);
  }

  @Test
  void deregister_unknownDevice_returns404() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .delete("/api/v1/devices/does-not-exist")
        .then()
        .statusCode(404);
  }
}
