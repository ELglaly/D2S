package com.schoolbridge.api.architecture;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.AbstractIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Validates that the OpenAPI spec is generated correctly and contains all expected tags, paths, and
 * security schemes. Also writes the spec to docs/api/ as a committed artifact for the Flutter team
 * to use with openapi-generator / swagger_parser.
 *
 * <p>This test replaces the springdoc-openapi-maven-plugin (which requires a live server on :8080
 * during the integration-test phase and silently no-ops in CI).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocsTest extends AbstractIntegrationTest {

  private static final List<String> EXPECTED_TAGS =
      List.of(
          "Auth",
          "Parent Auth",
          "Devices",
          "Schools",
          "Classes",
          "Students",
          "Enrollments",
          "Subjects",
          "Class Subjects",
          "Teacher Subject Assignments",
          "Parent-Student Links",
          "Announcements",
          "Attendance",
          "WhatsApp Webhook");

  @LocalServerPort int port;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
  }

  @Test
  void openApiJsonIsValidAndContainsAllExpectedTags() throws IOException {
    Response response =
        given()
            .accept("application/json")
            .when()
            .get("/v3/api-docs")
            .then()
            .statusCode(200)
            .extract()
            .response();

    String body = response.asString();
    ObjectMapper mapper = new ObjectMapper();
    JsonNode spec = mapper.readTree(body);

    assertThat(spec.has("openapi")).as("spec must have 'openapi' version field").isTrue();
    assertThat(spec.has("info")).as("spec must have 'info' section").isTrue();
    assertThat(spec.has("paths")).as("spec must have 'paths' section").isTrue();
    assertThat(spec.has("components")).as("spec must have 'components' section").isTrue();

    JsonNode securitySchemes = spec.path("components").path("securitySchemes");
    assertThat(securitySchemes.has("BearerJWT"))
        .as("BearerJWT security scheme must be defined")
        .isTrue();
    assertThat(securitySchemes.has("ParentToken"))
        .as("ParentToken security scheme must be defined")
        .isTrue();

    JsonNode schemas = spec.path("components").path("schemas");
    assertThat(schemas.has("ProblemDetail"))
        .as("ProblemDetail schema must be registered as a reusable component")
        .isTrue();

    JsonNode tags = spec.path("tags");
    assertThat(tags.isArray()).as("spec must have a 'tags' array").isTrue();
    List<String> tagNames = tags.findValuesAsText("name").stream().toList();
    assertThat(tagNames)
        .as("all expected controller tags must appear in the spec")
        .containsAll(EXPECTED_TAGS);

    assertNoXSchoolIdParameter(spec);

    writeArtifacts(body, mapper);
  }

  @Test
  void openApiYamlIsReachable() {
    given()
        .accept("application/vnd.oai.openapi")
        .when()
        .get("/v3/api-docs.yaml")
        .then()
        .statusCode(200);
  }

  private void assertNoXSchoolIdParameter(JsonNode spec) {
    String specText = spec.toString();
    assertThat(specText)
        .as("No endpoint should declare an X-School-Id parameter (tenancy is token-derived)")
        .doesNotContainIgnoringCase("X-School-Id");
  }

  private void writeArtifacts(String jsonBody, ObjectMapper mapper) throws IOException {
    Path outDir = Paths.get("docs", "api");
    Files.createDirectories(outDir);

    Path jsonPath = outDir.resolve("openapi.json");
    Object parsed = mapper.readValue(jsonBody, Object.class);
    Files.writeString(jsonPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed));

    Response yamlResponse =
        given()
            .accept("application/vnd.oai.openapi")
            .when()
            .get("/v3/api-docs.yaml")
            .then()
            .statusCode(200)
            .extract()
            .response();
    Files.writeString(outDir.resolve("openapi.yaml"), yamlResponse.asString());
  }
}
