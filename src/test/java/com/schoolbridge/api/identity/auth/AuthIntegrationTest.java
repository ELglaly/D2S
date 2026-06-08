package com.schoolbridge.api.identity.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.identity.PlatformAdmin;
import com.schoolbridge.api.identity.PlatformAdminRepository;
import com.schoolbridge.api.identity.RefreshTokenRepository;
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
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort int port;
  @Autowired UserRepository userRepository;
  @Autowired PlatformAdminRepository platformAdminRepository;
  @Autowired RefreshTokenRepository refreshTokenRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JwtService jwtService;
  @Autowired StringRedisTemplate redis;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    refreshTokenRepository.deleteAll();
    userRepository.deleteAll();
    platformAdminRepository.deleteAll();
    schoolRepository.deleteAll();
    // Reset rate-limit counters from prior tests.
    var keys = redis.keys("login:fail:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
  }

  @Test
  void login_validPlatformAdmin_returnsTokens() {
    PlatformAdmin admin = seedPlatformAdmin("admin@platform.test", "correct-horse-staple");

    Response response =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("email", admin.getEmail(), "password", "correct-horse-staple"))
            .when()
            .post("/api/v1/auth/login")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(200)
            .body("data.accessToken", notNullValue())
            .body("data.refreshToken", notNullValue())
            .body("data.tokenType", equalTo("Bearer"))
            .extract()
            .response();

    var claims = jwtService.parse(response.path("data.accessToken"));
    assertThat(claims.get("kind", String.class)).isEqualTo("PLATFORM_ADMIN");
    assertThat(claims.get("role", String.class)).isEqualTo("SUPER_ADMIN");
  }

  @Test
  void login_validStaffUser_returnsTokensWithSchoolId() {
    School school = seedSchool();
    seedStaffUser(school.getId(), "teacher@x.test", "p@ssword", UserRole.TEACHER);

    Response response =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("email", "teacher@x.test", "password", "p@ssword"))
            .when()
            .post("/api/v1/auth/login")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(200)
            .extract()
            .response();

    var claims = jwtService.parse(response.path("data.accessToken"));
    assertThat(claims.get("kind", String.class)).isEqualTo("USER");
    assertThat(claims.get("role", String.class)).isEqualTo("TEACHER");
    assertThat(claims.get("schoolId", String.class)).isEqualTo(school.getId().toString());
  }

  @Test
  void login_badPassword_returns401() {
    seedPlatformAdmin("admin2@platform.test", "right-password");
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
    seedPlatformAdmin("admin3@platform.test", "right-password");

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
        .body(Map.of("email", "admin3@platform.test", "password", "right-password"))
        .when()
        .post("/api/v1/auth/login")
        .then()
        .statusCode(429)
        .body("type", equalTo("https://schoolbridge.app/errors/rate-limit"));
  }

  @Test
  void refresh_rotatesAndRevokesOldToken() {
    seedPlatformAdmin("admin4@platform.test", "admin-password");
    Response login =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("email", "admin4@platform.test", "password", "admin-password"))
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
    seedPlatformAdmin("admin5@platform.test", "admin-password");
    Response login =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("email", "admin5@platform.test", "password", "admin-password"))
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

  private PlatformAdmin seedPlatformAdmin(String email, String password) {
    return platformAdminRepository.save(
        new PlatformAdmin(email, passwordEncoder.encode(password), "Test Admin"));
  }

  private School seedSchool() {
    return schoolRepository.save(
        new School(
            "Test School",
            "EG",
            "Africa/Cairo",
            "ar-EG",
            SubscriptionTier.STANDARD,
            SchoolSettings.defaults()));
  }

  private User seedStaffUser(
      java.util.UUID schoolId, String email, String password, UserRole role) {
    return userRepository.save(
        User.staff(schoolId, role, "Test " + role, email, passwordEncoder.encode(password)));
  }
}
