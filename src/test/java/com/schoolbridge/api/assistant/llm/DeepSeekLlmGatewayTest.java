package com.schoolbridge.api.assistant.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Pure (non-Spring) unit test for the DeepSeek adapter's wire shape and response parsing. WireMock
 * stands in for the NVIDIA NIM / DeepSeek chat-completions endpoint so we exercise the real
 * RestClient round-trip without network. Resilience4j AOP is not in play (no Spring proxy), so
 * {@code converse} runs straight through.
 *
 * <p>Covers: a plain-text completion (text + usage parsing), a tool call (id/name/arguments →
 * {@code LlmContent.ToolUse}, stopReason {@code tool_use}), arguments returned as a JSON object
 * (NVIDIA NIM variant of the OpenAI shape), and the request mapping of a multi-turn conversation
 * onto the OpenAI message format (assistant {@code tool_calls} + {@code role:"tool"} results).
 */
class DeepSeekLlmGatewayTest {

  // Includes the "/v1" base path: WireMock matching this proves the gateway preserves the base
  // path instead of replacing it with a leading-slash request path.
  private static final String PATH = "/v1/chat/completions";

  private final ObjectMapper mapper = new ObjectMapper();
  private WireMockServer wireMock;
  private DeepSeekLlmGateway gateway;

  @BeforeEach
  void setUp() {
    wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wireMock.start();
    RestClient client =
        RestClient.builder().defaultHeader("Authorization", "Bearer test-key").build();
    AssistantProperties properties = new AssistantProperties();
    // Base URL carries a "/v1"-style path so the test also pins the path-preservation fix: the
    // gateway must POST to {base}/chat/completions, not drop the base path.
    properties.setDeepseekBaseUrl(wireMock.baseUrl() + "/v1");
    gateway = new DeepSeekLlmGateway(client, mapper, properties);
  }

  @AfterEach
  void tearDown() {
    wireMock.stop();
  }

  @Test
  void converse_textResponse_parsesTextAndUsage() {
    stub(
        """
        {"choices":[{"message":{"role":"assistant","content":"You teach 1 class."},
        "finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":5}}
        """);

    LlmResponse response =
        gateway.converse(
            new LlmRequest(
                "system prompt",
                List.of(LlmMessage.user("summarize my classes")),
                List.of(),
                "m",
                256));

    assertThat(response.text()).isEqualTo("You teach 1 class.");
    assertThat(response.wantsTools()).isFalse();
    assertThat(response.stopReason()).isEqualTo("end_turn");
    assertThat(response.usage().inputTokens()).isEqualTo(12);
    assertThat(response.usage().outputTokens()).isEqualTo(5);

    var requests = wireMock.findAll(postRequestedFor(urlEqualTo(PATH)));
    assertThat(requests).hasSize(1);
    assertThat(requests.get(0).getHeader("Authorization")).isEqualTo("Bearer test-key");
    JsonNode body = readBody(requests.get(0).getBodyAsString());
    assertThat(body.path("model").asText()).isEqualTo("m");
    assertThat(body.path("max_tokens").asLong()).isEqualTo(256);
    assertThat(body.has("tools")).as("no tools array when none advertised").isFalse();
    JsonNode messages = body.path("messages");
    assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
    assertThat(messages.get(0).path("content").asText()).isEqualTo("system prompt");
    assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
    assertThat(messages.get(1).path("content").asText()).isEqualTo("summarize my classes");
  }

  @Test
  void converse_toolCallResponse_parsesToolUse() {
    stub(
        """
        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
        {"id":"call_1","type":"function","function":{"name":"mark_attendance",
        "arguments":"{\\"classRef\\":\\"3A\\",\\"status\\":\\"ABSENT\\"}"}}]},
        "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":40,"completion_tokens":20}}
        """);

    LlmResponse response =
        gateway.converse(
            new LlmRequest(
                "sys",
                List.of(LlmMessage.user("mark absent")),
                List.of(attendanceTool()),
                "m",
                256));

    assertThat(response.wantsTools()).isTrue();
    assertThat(response.stopReason()).isEqualTo("tool_use");
    assertThat(response.toolUses()).hasSize(1);
    LlmContent.ToolUse use = response.toolUses().get(0);
    assertThat(use.id()).isEqualTo("call_1");
    assertThat(use.name()).isEqualTo("mark_attendance");
    assertThat(use.input().path("classRef").asText()).isEqualTo("3A");
    assertThat(use.input().path("status").asText()).isEqualTo("ABSENT");

    // The advertised tool is sent in OpenAI function shape.
    JsonNode body =
        readBody(wireMock.findAll(postRequestedFor(urlEqualTo(PATH))).get(0).getBodyAsString());
    JsonNode tool = body.path("tools").get(0);
    assertThat(tool.path("type").asText()).isEqualTo("function");
    assertThat(tool.path("function").path("name").asText()).isEqualTo("mark_attendance");
    assertThat(tool.path("function").path("parameters").path("properties").has("classRef"))
        .isTrue();
  }

  @Test
  void converse_toolCallArgumentsAsObject_parsed() {
    // NVIDIA NIM sometimes returns tool-call arguments already parsed as an object, not a string.
    stub(
        """
        {"choices":[{"message":{"role":"assistant","tool_calls":[
        {"id":"call_2","type":"function","function":{"name":"mark_attendance",
        "arguments":{"classRef":"3A"}}}]},"finish_reason":"tool_calls"}]}
        """);

    LlmResponse response =
        gateway.converse(
            new LlmRequest(
                "sys",
                List.of(LlmMessage.user("mark absent")),
                List.of(attendanceTool()),
                "m",
                256));

    assertThat(response.toolUses()).hasSize(1);
    assertThat(response.toolUses().get(0).id()).isEqualTo("call_2");
    assertThat(response.toolUses().get(0).input().path("classRef").asText()).isEqualTo("3A");
    assertThat(response.usage()).isEqualTo(LlmUsage.zero());
  }

  @Test
  void converse_mapsToolResultsOntoOpenAiMessages() {
    stub("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}");

    ObjectNode args = mapper.createObjectNode();
    args.put("classRef", "3A");
    LlmRequest request =
        new LlmRequest(
            "sys",
            List.of(
                LlmMessage.user("mark absent"),
                LlmMessage.assistant(
                    List.of(new LlmContent.ToolUse("call_1", "mark_attendance", args))),
                LlmMessage.user(
                    List.of(new LlmContent.ToolResult("call_1", "{\"ok\":true}", false)))),
            List.of(attendanceTool()),
            "m",
            256);

    gateway.converse(request);

    JsonNode body =
        readBody(wireMock.findAll(postRequestedFor(urlEqualTo(PATH))).get(0).getBodyAsString());
    JsonNode messages = body.path("messages");
    // system, user, assistant(tool_calls), tool(result)
    assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
    assertThat(messages.get(1).path("role").asText()).isEqualTo("user");

    JsonNode assistant = messages.get(2);
    assertThat(assistant.path("role").asText()).isEqualTo("assistant");
    JsonNode call = assistant.path("tool_calls").get(0);
    assertThat(call.path("id").asText()).isEqualTo("call_1");
    assertThat(call.path("type").asText()).isEqualTo("function");
    assertThat(call.path("function").path("name").asText()).isEqualTo("mark_attendance");
    assertThat(call.path("function").path("arguments").asText()).contains("\"classRef\":\"3A\"");

    JsonNode toolResult = messages.get(3);
    assertThat(toolResult.path("role").asText()).isEqualTo("tool");
    assertThat(toolResult.path("tool_call_id").asText()).isEqualTo("call_1");
    assertThat(toolResult.path("content").asText()).isEqualTo("{\"ok\":true}");
  }

  // --- helpers --------------------------------------------------------------

  private void stub(String jsonBody) {
    wireMock.stubFor(
        post(urlEqualTo(PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(jsonBody)));
  }

  private LlmToolSpec attendanceTool() {
    ObjectNode schema = mapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("classRef").put("type", "string");
    properties.putObject("status").put("type", "string");
    schema.putArray("required").add("classRef");
    return new LlmToolSpec("mark_attendance", "Mark a student's attendance", schema);
  }

  private JsonNode readBody(String raw) {
    try {
      return mapper.readTree(raw);
    } catch (Exception e) {
      throw new IllegalStateException("bad request body: " + raw, e);
    }
  }
}
