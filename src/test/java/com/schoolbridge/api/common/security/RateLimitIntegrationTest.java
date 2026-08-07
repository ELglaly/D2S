package com.schoolbridge.api.common.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import io.restassured.RestAssured;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Proves the blanket cap is actually wired and returns a proper 429.
 *
 * <p>The limit is squeezed to 3/minute here so the test is fast. Two things are asserted beyond
 * "some request failed": that the rejection is a 429 (not a 500 from an unhandled interceptor
 * exception, which is what a servlet-filter implementation would produce) and that actuator stays
 * reachable, since throttling health checks would make a traffic spike look like an outage.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "schoolbridge.rate-limit.enabled=true",
      "schoolbridge.rate-limit.anonymous-per-minute=3",
      "schoolbridge.rate-limit.authenticated-per-minute=3"
    })
class RateLimitIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort int port;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
  }

  @Test
  void anonymousCallerOnPublicEndpointIsCappedWith429() {
    Set<Integer> observed = new HashSet<>();
    for (int i = 0; i < 12; i++) {
      observed.add(
          given()
              .contentType("application/json")
              .body("{\"phone\":\"+201000000000\"}")
              .when()
              .post("/api/v1/parents/auth/request-otp")
              .then()
              .extract()
              .statusCode());
    }

    assertThat(observed)
        .as("the cap must engage and surface as 429, not a 500 or a container error page")
        .contains(429);
  }

  @Test
  void actuatorIsNotRateLimited() {
    for (int i = 0; i < 12; i++) {
      given().when().get("/actuator/health").then().statusCode(200);
    }
  }
}
