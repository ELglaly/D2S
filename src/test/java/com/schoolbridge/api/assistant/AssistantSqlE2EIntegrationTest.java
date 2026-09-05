package com.schoolbridge.api.assistant;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.assistant.llm.LlmContent;
import com.schoolbridge.api.assistant.llm.LlmGateway;
import com.schoolbridge.api.assistant.llm.LlmResponse;
import com.schoolbridge.api.assistant.llm.LlmUsage;
import io.restassured.http.ContentType;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.context.jdbc.SqlMergeMode;

/** SQL-backed HTTP/E2E coverage for the enabled assistant module. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "schoolbridge.assistant.enabled=true",
      "schoolbridge.assistant.actions.enabled=false",
      "spring.ai.model.chat=openai",
      "spring.ai.openai.api-key=test-key"
    })
@Sql(
    scripts = {
      "classpath:sql/cleanup/all-data.sql",
      "classpath:sql/fixtures/common/schools.sql",
      "classpath:sql/fixtures/common/principals.sql",
      "classpath:sql/fixtures/common/role-permissions.sql",
      "classpath:sql/fixtures/common/assistant-context.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@SqlMergeMode(SqlMergeMode.MergeMode.OVERRIDE)
@SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED)
@Sql(
    scripts = "classpath:sql/cleanup/all-data.sql",
    executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class AssistantSqlE2EIntegrationTest extends AbstractIntegrationTest {

  private static final UUID CONVERSATION_ID =
      UUID.fromString("60000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_CONVERSATION_ID =
      UUID.fromString("60000000-0000-0000-0000-000000000002");

  @TestConfiguration
  static class GatewayConfiguration {
    @Bean
    @Primary
    ScriptedGateway scriptedGateway() {
      return new ScriptedGateway();
    }
  }

  static final class ScriptedGateway implements LlmGateway {
    private final ArrayDeque<LlmResponse> responses = new ArrayDeque<>();

    void nextText(String text) {
      responses.clear();
      responses.add(
          new LlmResponse(List.of(new LlmContent.Text(text)), "end_turn", LlmUsage.zero()));
    }

    @Override
    public LlmResponse converse(com.schoolbridge.api.assistant.llm.LlmRequest request) {
      return responses.isEmpty()
          ? new LlmResponse(
              List.of(new LlmContent.Text("default response")), "end_turn", LlmUsage.zero())
          : responses.poll();
    }
  }

  @Autowired ScriptedGateway gateway;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @LocalServerPort int port;

  @BeforeEach
  void resetGateway() {
    io.restassured.RestAssured.port = port;
    com.schoolbridge.api.common.tenancy.TenantContext.clear();
    gateway.nextText("one class");
  }

  private String login(String email, String password) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("email", email, "password", password))
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .path("data.accessToken");
  }

  @Test
  void conversationLifecycleStreamsAndPersistsMessagesAndAudit() {
    String teacher = login("teacher@fixture.test", "password");
    given()
        .header("Authorization", "Bearer " + teacher)
        .get("/api/v1/conversations")
        .then()
        .statusCode(200)
        .body(
            "data.find { it.id == '" + CONVERSATION_ID + "' }.title",
            org.hamcrest.Matchers.equalTo("Fixture assistant thread"));

    String id =
        given()
            .header("Authorization", "Bearer " + teacher)
            .contentType(ContentType.JSON)
            .body(Map.of("title", "HTTP assistant journey"))
            .post("/api/v1/conversations")
            .then()
            .statusCode(201)
            .extract()
            .path("data.id");

    gateway.nextText("The answer is streamed.");
    String sse =
        given()
            .header("Authorization", "Bearer " + teacher)
            .contentType(ContentType.JSON)
            .body(Map.of("content", "summarize my classes"))
            .post("/api/v1/conversations/" + id + "/messages")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertThat(sse)
        .contains("event: message_start", "event: content_block_delta", "event: message_stop");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from assistant_messages where conversation_id = ?",
                Integer.class,
                UUID.fromString(id)))
        .isEqualTo(2);

    given()
        .header("Authorization", "Bearer " + teacher)
        .delete("/api/v1/conversations/" + id)
        .then()
        .statusCode(204);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from assistant_conversations where id = ?",
                Integer.class,
                UUID.fromString(id)))
        .isZero();
  }

  @Test
  void validationPermissionsAndTenantOwnershipAreEnforced() {
    String teacher = login("teacher@fixture.test", "password");
    given()
        .header("Authorization", "Bearer " + teacher)
        .delete("/api/v1/conversations/" + OTHER_CONVERSATION_ID)
        .then()
        .statusCode(404);
    given()
        .header("Authorization", "Bearer " + teacher)
        .contentType(ContentType.JSON)
        .body(Map.of("content", ""))
        .post("/api/v1/conversations/" + CONVERSATION_ID + "/messages")
        .then()
        .statusCode(422);
    given()
        .header("Authorization", "Bearer " + teacher)
        .get("/api/v1/assistant/knowledge")
        .then()
        .statusCode(403);
  }

  @Test
  void settingsAndDocumentIngestionPersistThroughPostgresAndVectorStore() {
    String admin = login("school-admin@fixture.test", "password");
    String teacher = login("teacher@fixture.test", "password");
    given()
        .header("Authorization", "Bearer " + admin)
        .get("/api/v1/assistant/settings")
        .then()
        .statusCode(200)
        .body(
            "data.systemPrompt",
            org.hamcrest.Matchers.equalTo("Be concise and helpful to school staff."));
    given()
        .header("Authorization", "Bearer " + admin)
        .contentType(ContentType.JSON)
        .body(Map.of("systemPrompt", "Use the school's concise handbook style."))
        .put("/api/v1/assistant/settings")
        .then()
        .statusCode(200);
    assertThat(
            jdbc.queryForObject(
                "select system_prompt from assistant_settings where school_id = '10000000-0000-0000-0000-000000000001'::uuid",
                String.class))
        .isEqualTo("Use the school's concise handbook style.");
    given()
        .header("Authorization", "Bearer " + teacher)
        .get("/api/v1/assistant/settings")
        .then()
        .statusCode(403);

    int before = jdbc.queryForObject("select count(*) from assistant_vector_store", Integer.class);
    String id =
        given()
            .header("Authorization", "Bearer " + admin)
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "type",
                    "GUIDE",
                    "title",
                    "HTTP guide",
                    "lang",
                    "en",
                    "content",
                    "Attendance is recorded each school day."))
            .post("/api/v1/assistant/knowledge")
            .then()
            .statusCode(201)
            .extract()
            .path("data.id");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from assistant_documents where id = ? and status = 'INDEXED' and chunk_count > 0",
                Integer.class,
                UUID.fromString(id)))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from assistant_vector_store", Integer.class))
        .isGreaterThan(before);
    given()
        .header("Authorization", "Bearer " + admin)
        .delete("/api/v1/assistant/knowledge/" + id)
        .then()
        .statusCode(204);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from assistant_documents where id = ?",
                Integer.class,
                UUID.fromString(id)))
        .isZero();
  }

  @Test
  void askSseValidationAndConfirmationRoutesHaveStableContracts() throws Exception {
    String teacher = login("teacher@fixture.test", "password");
    String sse =
        given()
            .header("Authorization", "Bearer " + teacher)
            .contentType(ContentType.JSON)
            .body(Map.of("question", "What is my schedule?"))
            .post("/api/v1/assistant/ask")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    String firstData = sse.substring(sse.indexOf("data:") + 5).trim().split("\\R", 2)[0];
    assertThat(mapper.readTree(firstData).path("type").asText()).isEqualTo("delta");
    assertThat(sse).contains("event: done");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'assistant.ask' and school_id = '10000000-0000-0000-0000-000000000001'::uuid",
                Integer.class))
        .isEqualTo(1);
    given()
        .header("Authorization", "Bearer " + teacher)
        .contentType(ContentType.JSON)
        .body(Map.of("question", ""))
        .post("/api/v1/assistant/ask")
        .then()
        .statusCode(422);
    given()
        .header("Authorization", "Bearer " + teacher)
        .post("/api/v1/assistant/actions/not-a-real-token/cancel")
        .then()
        .statusCode(200)
        .body("data.status", org.hamcrest.Matchers.equalTo("INVALID"));
    given()
        .header("Authorization", "Bearer " + teacher)
        .contentType(ContentType.JSON)
        .body(Map.of("confirmation", "yes"))
        .post("/api/v1/assistant/actions/not-a-real-token/confirm")
        .then()
        .statusCode(200)
        .body("data.status", org.hamcrest.Matchers.equalTo("INVALID"));
  }
}
