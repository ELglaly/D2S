package com.schoolbridge.api;

import static io.restassured.RestAssured.given;

import com.schoolbridge.api.common.tenancy.TenantContext;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

/** Base for HTTP and E2E integration tests that import deterministic PostgreSQL fixtures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED)
@Sql(
    scripts = {
      "classpath:sql/cleanup/all-data.sql",
      "classpath:sql/fixtures/common/schools.sql",
      "classpath:sql/fixtures/common/principals.sql",
      "classpath:sql/fixtures/common/role-permissions.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
    scripts = "classpath:sql/cleanup/all-data.sql",
    executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
public abstract class SqlIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort protected int port;

  @BeforeEach
  void prepareSqlIntegrationTest() {
    TenantContext.clear();
    RestAssured.port = port;
  }

  /** Logs in through the real authentication endpoint and returns the issued bearer token. */
  protected String login(String email, String password) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("email", email, "password", password))
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .path("data.accessToken");
  }

  /** Starts a request authenticated with a token issued by {@link #login(String, String)}. */
  protected RequestSpecification authenticated(String accessToken) {
    return given().header("Authorization", "Bearer " + accessToken);
  }

  /** Extracts an API envelope identifier without coupling a test to a repository setup helper. */
  protected UUID responseId(Response response) {
    return UUID.fromString(response.path("data.id"));
  }
}
