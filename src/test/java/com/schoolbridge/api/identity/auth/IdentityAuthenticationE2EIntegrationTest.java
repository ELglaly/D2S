package com.schoolbridge.api.identity.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.SqlIntegrationTest;
import com.schoolbridge.api.identity.otp.OtpDispatcher;
import io.restassured.http.ContentType;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Multi-request authentication journeys using only credentials issued by the public API. */
@Import(IdentityAuthenticationE2EIntegrationTest.CaptureConfig.class)
class IdentityAuthenticationE2EIntegrationTest extends SqlIntegrationTest {

  @Autowired CapturingOtpDispatcher dispatcher;

  @BeforeEach
  void clearCapturedCode() {
    dispatcher.code.set(null);
  }

  @Test
  void staffJourneyLogsInRefreshesLogsOutAndCannotReuseRefreshCredential() {
    String refresh =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("email", "teacher@fixture.test", "password", "password"))
            .post("/api/v1/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .path("data.refreshToken");
    String rotated =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("refreshToken", refresh))
            .post("/api/v1/auth/refresh")
            .then()
            .statusCode(200)
            .extract()
            .path("data.refreshToken");
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("refreshToken", rotated))
        .post("/api/v1/auth/logout")
        .then()
        .statusCode(204);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("refreshToken", rotated))
        .post("/api/v1/auth/refresh")
        .then()
        .statusCode(401);
  }

  @Test
  void parentJourneyExchangesOtpUsesProtectedRouteAndLogoutRevokesSession() {
    String ticket =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("phone", "+201090000201"))
            .post("/api/v1/parents/auth/request-otp")
            .then()
            .statusCode(200)
            .extract()
            .path("data.ticketId");
    String token =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("ticketId", ticket, "code", dispatcher.code.get()))
            .post("/api/v1/parents/auth/verify-otp")
            .then()
            .statusCode(200)
            .extract()
            .path("data.token");
    assertThat(token).isNotBlank();
    authenticated(token).get("/api/v1/notifications/preferences").then().statusCode(200);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("token", token))
        .post("/api/v1/parents/auth/logout")
        .then()
        .statusCode(204);
    authenticated(token).get("/api/v1/notifications/preferences").then().statusCode(401);
  }

  @TestConfiguration
  static class CaptureConfig {
    @Bean
    @Primary
    CapturingOtpDispatcher capturingOtpDispatcher() {
      return new CapturingOtpDispatcher();
    }
  }

  static class CapturingOtpDispatcher implements OtpDispatcher {
    final AtomicReference<String> code = new AtomicReference<>();

    @Override
    public void dispatch(String phone, String code) {
      this.code.set(code);
    }
  }
}
