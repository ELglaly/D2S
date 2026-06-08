package com.schoolbridge.api.identity.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.otp.OtpDispatcher;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(ParentAuthIntegrationTest.CaptureConfig.class)
class ParentAuthIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort int port;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired CapturingOtpDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    userRepository.deleteAll();
    schoolRepository.deleteAll();
    dispatcher.lastCode.set(null);
  }

  @Test
  void requestOtp_thenVerifyOtp_issuesParentToken() {
    School school = seedSchool();
    seedParent(school.getId(), "+201111111111");

    String ticketId =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("phone", "+201111111111"))
            .when()
            .post("/api/v1/parents/auth/request-otp")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(200)
            .body("data.ticketId", notNullValue())
            .extract()
            .path("data.ticketId");

    String code = dispatcher.lastCode.get();
    assertThat(code).as("LoggingOtpDispatcher was replaced; capture must have a code").isNotNull();

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("ticketId", ticketId, "code", code))
        .when()
        .post("/api/v1/parents/auth/verify-otp")
        .then()
        .statusCode(200)
        .body("data.token", notNullValue())
        .body("data.schoolId", equalTo(school.getId().toString()))
        .body("data.tokenType", equalTo("Bearer"));
  }

  @Test
  void requestOtp_forUnknownPhone_stillReturnsTicketIdButNoDispatch() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("phone", "+209999999999"))
        .when()
        .post("/api/v1/parents/auth/request-otp")
        .then()
        .statusCode(200)
        .body("data.ticketId", notNullValue());

    assertThat(dispatcher.lastCode.get())
        .as("unknown phone must not trigger an OTP dispatch — anti-enumeration")
        .isNull();
  }

  @Test
  void verifyOtp_withWrongCode_returns401() {
    School school = seedSchool();
    seedParent(school.getId(), "+201222222222");

    String ticketId =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("phone", "+201222222222"))
            .when()
            .post("/api/v1/parents/auth/request-otp")
            .then()
            .extract()
            .path("data.ticketId");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("ticketId", ticketId, "code", "000000"))
        .when()
        .post("/api/v1/parents/auth/verify-otp")
        .then()
        .statusCode(401);
  }

  @Test
  void verifyOtp_unknownTicket_returns401() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("ticketId", java.util.UUID.randomUUID().toString(), "code", "123456"))
        .when()
        .post("/api/v1/parents/auth/verify-otp")
        .then()
        .statusCode(401);
  }

  private School seedSchool() {
    return schoolRepository.save(
        new School(
            "Parent Test School",
            "EG",
            "Africa/Cairo",
            "ar-EG",
            SubscriptionTier.STANDARD,
            SchoolSettings.defaults()));
  }

  private void seedParent(java.util.UUID schoolId, String phone) {
    userRepository.save(User.parent(schoolId, "Parent " + phone, phone, blindIndex.hash(phone)));
  }

  /**
   * Test-only OTP dispatcher that captures the most recent code so the verify-otp endpoint can be
   * exercised end-to-end. Marked {@code @Primary} so it wins over the dev-only logging dispatcher.
   */
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
