package com.schoolbridge.api.identity.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.identity.jwt.JwtService;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

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
class AuthIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort int port;
  @Autowired JwtService jwtService;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    // Reset rate-limit counters from prior tests.
    var keys = redis.keys("login:fail:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
  }

  @Test
  void login_validPlatformAdmin_returnsTokens() {
    Response response =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("email", "admin@platform.test", "password", "password"))
            .when()
            .post("/api/v1/auth/login")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(200)
            .body("data.accessToken", notNullValue())
            .body("data.refreshToken", notNullValue())
            .body("data.tokenType", equalTo("Bearer"))
            .body("data.role", equalTo("SUPER_ADMIN"))
            .extract()
            .response();

    var claims = jwtService.parse(response.path("data.accessToken"));
    assertThat(claims.get("kind", String.class)).isEqualTo("PLATFORM_ADMIN");
    assertThat(claims.get("role", String.class)).isEqualTo("SUPER_ADMIN");
  }

  @Test
  void login_validStaffUser_returnsTokensWithSchoolId() {
    Response response =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("email", "teacher@x.test", "password", "password"))
            .when()
            .post("/api/v1/auth/login")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(200)
            .body("data.role", equalTo("TEACHER"))
            .extract()
            .response();

    var claims = jwtService.parse(response.path("data.accessToken"));
    assertThat(claims.get("kind", String.class)).isEqualTo("USER");
    assertThat(claims.get("role", String.class)).isEqualTo("TEACHER");
    assertThat(claims.get("schoolId", String.class))
        .isEqualTo("10000000-0000-0000-0000-000000000001");
  }

  @Test
  void login_badPassword_returns401() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("email", "admin2@platform.test", "password", "wrong-password"))
        .when()
        .post("/api/v1/auth/login")
        .then()
        .statusCode(401)
        .body("type", equalTo("https://schoolbridge.app/errors/authentication"));
  }

  @Test
  void login_unknownEmail_returns401() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("email", "nobody@nowhere.test", "password", "whatever12"))
        .when()
        .post("/api/v1/auth/login")
        .then()
        .statusCode(401);
  }

  @Test
  void login_sixthAttempt_isRateLimited() {
    // Five bad attempts → all 401, counter reaches 5.
    for (int i = 0; i < 5; i++) {
      given()
          .contentType(ContentType.JSON)
          .body(Map.of("email", "admin3@platform.test", "password", "wrong-password"))
          .when()
          .post("/api/v1/auth/login")
          .then()
          .statusCode(401);
    }
    // Sixth attempt — limiter trips before credentials are even checked.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("email", "admin3@platform.test", "password", "password"))
        .when()
        .post("/api/v1/auth/login")
        .then()
        .statusCode(429)
        .body("type", equalTo("https://schoolbridge.app/errors/rate-limit"));
  }

  @Test
  void refresh_rotatesAndRevokesOldToken() {
    Response login =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("email", "admin4@platform.test", "password", "password"))
            .when()
            .post("/api/v1/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .response();
    String firstRefresh = login.path("data.refreshToken");

    Response refreshed =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("refreshToken", firstRefresh))
            .when()
            .post("/api/v1/auth/refresh")
            .then()
            .statusCode(200)
            .extract()
            .response();
    String secondRefresh = refreshed.path("data.refreshToken");
    assertThat(secondRefresh).isNotEqualTo(firstRefresh);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_tokens where revoked_at is not null", Long.class))
        .isEqualTo(1L);

    // Replaying the original refresh must be rejected — it's been marked revoked.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("refreshToken", firstRefresh))
        .when()
        .post("/api/v1/auth/refresh")
        .then()
        .statusCode(401);
  }

  @Test
  void logout_revokesRefreshToken() {
    Response login =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("email", "admin5@platform.test", "password", "password"))
            .when()
            .post("/api/v1/auth/login")
            .then()
            .extract()
            .response();
    String refresh = login.path("data.refreshToken");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("refreshToken", refresh))
        .when()
        .post("/api/v1/auth/logout")
        .then()
        .statusCode(204);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("refreshToken", refresh))
        .when()
        .post("/api/v1/auth/refresh")
        .then()
        .statusCode(401);
  }

  @Test
  void malformedAndInvalidRefreshRequestsReturnExpectedErrors() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("email", "not-an-email", "password", "x"))
        .post("/api/v1/auth/login")
        .then()
        .statusCode(422);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("refreshToken", "not-a-real-token"))
        .post("/api/v1/auth/refresh")
        .then()
        .statusCode(401);
  }

  @Test
  void expiredRefreshTokenIsRejectedAndItsPersistedStateIsVisible() {
    Response login =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("email", "admin@platform.test", "password", "password"))
            .post("/api/v1/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .response();
    String refresh = login.path("data.refreshToken");
    jdbc.update(
        "update refresh_tokens set expires_at = current_timestamp - interval '1 second' where token_hash = ?",
        jwtService.hashRefresh(refresh));

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("refreshToken", refresh))
        .post("/api/v1/auth/refresh")
        .then()
        .statusCode(401);
  }
}
