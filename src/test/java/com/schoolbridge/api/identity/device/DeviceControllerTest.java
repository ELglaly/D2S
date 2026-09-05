package com.schoolbridge.api.identity.device;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.SqlIntegrationTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DeviceControllerTest extends SqlIntegrationTest {

  @Autowired JdbcTemplate jdbc;
  private String adminToken;

  @BeforeEach
  void setUp() {
    adminToken = login("school-admin@fixture.test", "password");
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

    assertThat(
            jdbc.queryForObject(
                "select count(*) from device_tokens where device_id = ?", Long.class, "dev-upsert"))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "select fcm_token from device_tokens where device_id = ?",
                String.class,
                "dev-upsert"))
        .isEqualTo("fcm-new-token");
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

    assertThat(
            jdbc.queryForObject(
                "select active from device_tokens where device_id = ?",
                Boolean.class,
                "dev-to-delete"))
        .isFalse();
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

  @Test
  void anotherAuthenticatedUserCannotDeregisterOwnersDevice() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("platform", "ANDROID", "fcmToken", "fcm-owned", "deviceId", "owner-device"))
        .post("/api/v1/devices/register")
        .then()
        .statusCode(200);

    String teacherToken = login("teacher@fixture.test", "password");
    authenticated(teacherToken).delete("/api/v1/devices/owner-device").then().statusCode(404);
    assertThat(
            jdbc.queryForObject(
                "select active from device_tokens where device_id = ?",
                Boolean.class,
                "owner-device"))
        .isTrue();
  }
}
