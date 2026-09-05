package com.schoolbridge.api.identity.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

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
import org.springframework.data.redis.core.StringRedisTemplate;

/** Parent OTP lifecycle using SQL identities, Redis tickets, and the real security filter. */
@Import(ParentAuthIntegrationTest.CaptureConfig.class)
class ParentAuthIntegrationTest extends SqlIntegrationTest {

  @Autowired CapturingOtpDispatcher dispatcher;
  @Autowired StringRedisTemplate redis;

  @BeforeEach
  void clearAuthState() {
    dispatcher.lastCode.set(null);
    var keys = redis.keys("otp:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    keys = redis.keys("parent:token:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void requestVerifyLogout_thenRevokedParentTokenIsRejected() {
    String ticketId = requestOtp("+201090000201");
    String token = verify(ticketId, dispatcher.lastCode.get());

    authenticated(token).get("/api/v1/notifications/preferences").then().statusCode(200);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("token", token))
        .post("/api/v1/parents/auth/logout")
        .then()
        .statusCode(204);
    authenticated(token).get("/api/v1/notifications/preferences").then().statusCode(401);
  }

  @Test
  void unknownPhoneDoesNotDispatchAndWrongOrReplayedOtpIsRejected() {
    requestOtp("+209999999999");
    assertThat(dispatcher.lastCode.get()).isNull();

    String ticketId = requestOtp("+201090000201");
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("ticketId", ticketId, "code", "000000"))
        .post("/api/v1/parents/auth/verify-otp")
        .then()
        .statusCode(401);
    String token = verify(ticketId, dispatcher.lastCode.get());
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("ticketId", ticketId, "code", dispatcher.lastCode.get()))
        .post("/api/v1/parents/auth/verify-otp")
        .then()
        .statusCode(401);
    assertThat(token).isNotBlank();
  }

  @Test
  void malformedAndUnknownOtpRequestsReturnProblemResponses() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("phone", "not-a-phone"))
        .post("/api/v1/parents/auth/request-otp")
        .then()
        .statusCode(422);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("ticketId", java.util.UUID.randomUUID().toString(), "code", "123456"))
        .post("/api/v1/parents/auth/verify-otp")
        .then()
        .statusCode(401);
  }

  @Test
  void expiredOtpTicketAndMalformedParentBearerTokenAreRejected() {
    String ticketId = requestOtp("+201090000201");
    redis.delete("otp:ticket:" + ticketId);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("ticketId", ticketId, "code", dispatcher.lastCode.get()))
        .post("/api/v1/parents/auth/verify-otp")
        .then()
        .statusCode(401);
    authenticated("expired-parent-token")
        .get("/api/v1/notifications/preferences")
        .then()
        .statusCode(401);
  }

  private String requestOtp(String phone) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("phone", phone))
        .post("/api/v1/parents/auth/request-otp")
        .then()
        .statusCode(200)
        .body("data.ticketId", notNullValue())
        .extract()
        .path("data.ticketId");
  }

  private String verify(String ticketId, String code) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("ticketId", ticketId, "code", code))
        .post("/api/v1/parents/auth/verify-otp")
        .then()
        .statusCode(200)
        .body("data.token", notNullValue())
        .extract()
        .path("data.token");
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
    final AtomicReference<String> lastCode = new AtomicReference<>();

    @Override
    public void dispatch(String phone, String code) {
      lastCode.set(code);
    }
  }
}
