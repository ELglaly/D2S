package com.schoolbridge.api.assistant.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link LlmGateway} backed by the DeepSeek chat-completions API (OpenAI-compatible). Loaded only
 * when {@code schoolbridge.assistant.enabled=true} and {@code provider=deepseek}.
 *
 * <p>DeepSeek uses the OpenAI message/tool-call wire format, which differs from our internal {@link
 * LlmMessage} model in two ways this conversion handles: tool calls live in an assistant message's
 * {@code tool_calls} array (not as content blocks), and every tool result is its own {@code
 * role:"tool"} message keyed by {@code tool_call_id}. Like Anthropic (and unlike Gemini), DeepSeek
 * links results to calls by an opaque id, so {@code LlmContent.ToolUse.id} carries the call id the
 * orchestrator echoes back in {@code ToolResult.toolUseId}.
 */
@Component
@ConditionalOnExpression(
    "'${schoolbridge.assistant.provider:anthropic}'.equals('deepseek')"
        + " and ${schoolbridge.assistant.enabled:false}")
public class DeepSeekLlmGateway implements LlmGateway {

  private static final String COMPLETIONS_PATH = "/chat/completions";

  private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmGateway.class);

  private final RestClient client;
  private final ObjectMapper mapper;
  private final String completionsUrl;

  public DeepSeekLlmGateway(
      @Qualifier("deepSeekRestClient") RestClient client,
      ObjectMapper mapper,
      AssistantProperties properties) {
    this.client = client;
    this.mapper = mapper;
    // Append to the configured base as an absolute URL. Passing a path that starts with "/" to a
    // baseUrl-configured RestClient would make DefaultUriBuilderFactory REPLACE the base path,
    // dropping the host's "/v1" prefix (NVIDIA NIM) and yielding a 404. An absolute URL is used
    // verbatim, so any base path is preserved.
    String base = properties.getDeepseekBaseUrl();
    String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    this.completionsUrl = trimmed + COMPLETIONS_PATH;
    // Logged once at startup so the exact endpoint is visible without a debugger — a 404 here is
    // almost always a base-url mismatch (e.g. a DEEPSEEK_BASE_URL env override missing "/v1").
    log.info("deepseek_gateway_init url={} model={}", completionsUrl, properties.getModel());
  }

  @Override
  @CircuitBreaker(name = "assistant")
  public LlmResponse converse(LlmRequest request) {
    ObjectNode body = mapper.createObjectNode();
    body.put("model", request.model());
    body.put("max_tokens", request.maxTokens());
    body.set("messages", toMessages(request));
    ArrayNode tools = toTools(request.tools());
    if (!tools.isEmpty()) {
      body.set("tools", tools);
    }

    try {
      JsonNode response =
          client.post().uri(completionsUrl).body(body).retrieve().body(JsonNode.class);
      return toResponse(response);
    } catch (RestClientResponseException ex) {
      // Surface the exact URL + provider body: a 404 "page not found" means the path is wrong
      // (base-url), a JSON error means the path is right but the model/key/payload is rejected.
      log.warn(
          "deepseek_call_failed url={} status={} body={}",
          completionsUrl,
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      throw ex;
    }
  }

  private ArrayNode toMessages(LlmRequest request) {
    ArrayNode messages = mapper.createArrayNode();
    messages.add(textMessage("system", request.system()));
    for (LlmMessage message : request.messages()) {
      appendMessage(messages, message);
    }
    return messages;
  }

  /**
   * Appends one internal message as one or more OpenAI-shaped messages. An assistant turn maps to a
   * single message (text plus any {@code tool_calls}); a user turn fans out — plain text becomes a
   * {@code user} message and each tool result becomes its own {@code tool} message.
   */
  private void appendMessage(ArrayNode messages, LlmMessage message) {
    if (message.role() == LlmMessage.Role.ASSISTANT) {
      messages.add(toAssistantMessage(message));
      return;
    }
    StringBuilder text = new StringBuilder();
    for (LlmContent content : message.content()) {
      if (content instanceof LlmContent.Text t) {
        if (text.length() > 0) {
          text.append('\n');
        }
        text.append(t.text());
      } else if (content instanceof LlmContent.ToolResult result) {
        messages.add(toToolMessage(result));
      }
    }
    if (text.length() > 0) {
      messages.add(textMessage("user", text.toString()));
    }
  }

  private ObjectNode toAssistantMessage(LlmMessage message) {
    ObjectNode node = mapper.createObjectNode();
    node.put("role", "assistant");
    StringBuilder text = new StringBuilder();
    ArrayNode toolCalls = mapper.createArrayNode();
    for (LlmContent content : message.content()) {
      if (content instanceof LlmContent.Text t) {
        if (text.length() > 0) {
          text.append('\n');
        }
        text.append(t.text());
      } else if (content instanceof LlmContent.ToolUse toolUse) {
        toolCalls.add(toToolCall(toolUse));
      }
    }
    node.put("content", text.toString());
    if (!toolCalls.isEmpty()) {
      node.set("tool_calls", toolCalls);
    }
    return node;
  }

  private ObjectNode toToolCall(LlmContent.ToolUse toolUse) {
    ObjectNode call = mapper.createObjectNode();
    call.put("id", toolUse.id());
    call.put("type", "function");
    ObjectNode function = mapper.createObjectNode();
    function.put("name", toolUse.name());
    JsonNode input = toolUse.input();
    function.put("arguments", input == null || input.isNull() ? "{}" : input.toString());
    call.set("function", function);
    return call;
  }

  private ObjectNode toToolMessage(LlmContent.ToolResult result) {
    ObjectNode node = mapper.createObjectNode();
    node.put("role", "tool");
    node.put("tool_call_id", result.toolUseId());
    node.put("content", result.content());
    return node;
  }

  private ObjectNode textMessage(String role, String content) {
    ObjectNode node = mapper.createObjectNode();
    node.put("role", role);
    node.put("content", content);
    return node;
  }

  private ArrayNode toTools(List<LlmToolSpec> specs) {
    ArrayNode tools = mapper.createArrayNode();
    for (LlmToolSpec spec : specs) {
      ObjectNode function = mapper.createObjectNode();
      function.put("name", spec.name());
      function.put("description", spec.description());
      function.set("parameters", spec.inputSchema());
      ObjectNode tool = mapper.createObjectNode();
      tool.put("type", "function");
      tool.set("function", function);
      tools.add(tool);
    }
    return tools;
  }

  private LlmResponse toResponse(JsonNode response) {
    List<LlmContent> content = new ArrayList<>();
    JsonNode message = response == null ? null : response.path("choices").path(0).path("message");
    if (message != null && !message.isMissingNode()) {
      JsonNode text = message.path("content");
      if (text.isTextual() && !text.asText().isBlank()) {
        content.add(new LlmContent.Text(text.asText()));
      }
      for (JsonNode call : message.path("tool_calls")) {
        JsonNode function = call.path("function");
        content.add(
            new LlmContent.ToolUse(
                call.path("id").asText(),
                function.path("name").asText(),
                parseArguments(function.path("arguments"))));
      }
    }

    boolean wantsTools = content.stream().anyMatch(LlmContent.ToolUse.class::isInstance);
    String stopReason = wantsTools ? "tool_use" : "end_turn";

    JsonNode usage = response == null ? null : response.path("usage");
    LlmUsage llmUsage =
        usage == null || usage.isMissingNode()
            ? LlmUsage.zero()
            : new LlmUsage(
                usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0));

    return new LlmResponse(content, stopReason, llmUsage);
  }

  /**
   * Tool-call arguments arrive as a JSON-encoded string (OpenAI shape); some OpenAI-compatible
   * hosts return them already parsed. Handle both, and treat malformed JSON as empty so the tool
   * layer rejects it on schema validation rather than the gateway throwing.
   */
  private JsonNode parseArguments(JsonNode arguments) {
    if (arguments == null || arguments.isMissingNode() || arguments.isNull()) {
      return mapper.createObjectNode();
    }
    if (arguments.isObject()) {
      return arguments;
    }
    String raw = arguments.asText("");
    if (raw.isBlank()) {
      return mapper.createObjectNode();
    }
    try {
      return mapper.readTree(raw);
    } catch (JsonProcessingException e) {
      return mapper.createObjectNode();
    }
  }
}
