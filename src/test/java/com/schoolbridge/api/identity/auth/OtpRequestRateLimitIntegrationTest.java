package com.schoolbridge.api.identity.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.identity.otp.OtpDispatcher;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * Caps OTP requests per phone number.
 *
 * <p>This endpoint is {@code permitAll} and every accepted request costs a real WhatsApp template
 * or SMS send. Unlimited, it is an unbounded bill and a WhatsApp Business quality-rating risk that
 * needs no account compromise to exploit — which is why the assertion below counts
 * <b>dispatches</b> and not just HTTP status codes.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"schoolbridge.otp.max-requests-per-hour=3"})
@Import(OtpRequestRateLimitIntegrationTest.CountingConfig.class)
@Sql(
    scripts = {
      "classpath:sql/cleanup/all-data.sql",
      "classpath:sql/fixtures/common/schools.sql",
      "classpath:sql/fixtures/identity/otp-rate-parent.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
    scripts = "classpath:sql/cleanup/all-data.sql",
    executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class OtpRequestRateLimitIntegrationTest extends AbstractIntegrationTest {

  private static final String PHONE = "+201555000111";

  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired CountingOtpDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    dispatcher.count.set(0);
    // The limiter keys on the blind-index hash, so clear those keys rather than the whole database.
    redis.delete("otp:req:h:VYG9gbbPFuiczKAuzuzAnYPeUExPJHJ8DnOO6Fjp+Jc=");
    redis.delete("otp:req:d:VYG9gbbPFuiczKAuzuzAnYPeUExPJHJ8DnOO6Fjp+Jc=");
  }

  private int requestOtp(String phone) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("phone", phone))
        .when()
        .post("/api/v1/parents/auth/request-otp")
        .then()
        .extract()
        .statusCode();
  }

  @Test
  void dispatchesStopAtTheCapAndFurtherRequestsGet429() {
    for (int i = 0; i < 3; i++) {
      assertThat(requestOtp(PHONE)).isEqualTo(200);
    }
    assertThat(dispatcher.count.get()).isEqualTo(3);

    assertThat(requestOtp(PHONE)).as("over the cap must be a 429").isEqualTo(429);
    assertThat(requestOtp(PHONE)).isEqualTo(429);

    assertThat(dispatcher.count.get())
        .as("no further messages may be sent — this is the cost control, not just a status code")
        .isEqualTo(3);
  }

  @Test
  void unknownPhoneNeverConsumesBudgetAndStaysIndistinguishable() {
    String unknown = "+201555999888";
    for (int i = 0; i < 6; i++) {
      assertThat(requestOtp(unknown))
          .as("an unknown number must look identical to a known one — anti-enumeration")
          .isEqualTo(200);
    }
    assertThat(dispatcher.count.get()).isZero();

    // The real parent's budget is untouched by traffic aimed at a number that does not exist.
    assertThat(requestOtp(PHONE)).isEqualTo(200);
    assertThat(dispatcher.count.get()).isEqualTo(1);
  }

  @TestConfiguration
  static class CountingConfig {
    @Bean
    @Primary
    CountingOtpDispatcher countingOtpDispatcher() {
      return new CountingOtpDispatcher();
    }
  }

  /** Counts sends: the thing that actually costs money. */
  static class CountingOtpDispatcher implements OtpDispatcher {
    final AtomicInteger count = new AtomicInteger();

    @Override
    public void dispatch(String phone, String code) {
      count.incrementAndGet();
    }
  }
}
