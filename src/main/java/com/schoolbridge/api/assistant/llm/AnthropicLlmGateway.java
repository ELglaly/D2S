package com.schoolbridge.api.assistant.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The only {@link LlmGateway} that touches the network. Translates the SDK-free {@link LlmRequest}
 * / {@link LlmResponse} model to and from the Anthropic Messages API. Loaded only when {@code
 * schoolbridge.assistant.enabled=true}; every test runs against a scripted stub instead.
 */
@Component
@ConditionalOnProperty(prefix = "schoolbridge.assistant", name = "enabled", havingValue = "true")
public class AnthropicLlmGateway implements LlmGateway {

  private final AnthropicClient client;
  private final ObjectMapper mapper;

  public AnthropicLlmGateway(AnthropicClient client, ObjectMapper mapper) {
    this.client = client;
    this.mapper = mapper;
  }

  @Override
  @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "assistant")
  public LlmResponse converse(LlmRequest request) {
    MessageCreateParams.Builder builder =
        MessageCreateParams.builder()
            .model(Model.of(request.model()))
            .maxTokens(request.maxTokens())
            .system(request.system());
    for (LlmToolSpec spec : request.tools()) {
      builder.addTool(
          Tool.builder()
              .name(spec.name())
              .description(spec.description())
              .inputSchema(toInputSchema(spec.inputSchema()))
              .build());
    }
    for (LlmMessage message : request.messages()) {
      List<ContentBlockParam> blocks =
          message.content().stream().map(this::toContentBlockParam).toList();
      if (message.role() == LlmMessage.Role.USER) {
        builder.addUserMessageOfBlockParams(blocks);
      } else {
        builder.addAssistantMessageOfBlockParams(blocks);
      }
    }
    return toResponse(client.messages().create(builder.build()));
  }

  private Tool.InputSchema toInputSchema(JsonNode schema) {
    Tool.InputSchema.Builder builder = Tool.InputSchema.builder();
    JsonNode properties = schema.get("properties");
    if (properties != null && !properties.isNull()) {
      builder.properties(JsonValue.from(mapper.convertValue(properties, Map.class)));
    }
    JsonNode required = schema.get("required");
    if (required != null && required.isArray()) {
      builder.putAdditionalProperty(
          "required", JsonValue.from(mapper.convertValue(required, List.class)));
    }
    return builder.build();
  }

  private ContentBlockParam toContentBlockParam(LlmContent content) {
    if (content instanceof LlmContent.Text text) {
      return ContentBlockParam.ofText(TextBlockParam.builder().text(text.text()).build());
    }
    if (content instanceof LlmContent.ToolUse toolUse) {
      return ContentBlockParam.ofToolUse(
          ToolUseBlockParam.builder()
              .id(toolUse.id())
              .name(toolUse.name())
              .input(JsonValue.from(mapper.convertValue(toolUse.input(), Map.class)))
              .build());
    }
    LlmContent.ToolResult result = (LlmContent.ToolResult) content;
    return ContentBlockParam.ofToolResult(
        ToolResultBlockParam.builder()
            .toolUseId(result.toolUseId())
            .content(result.content())
            .isError(result.error())
            .build());
  }

  private LlmResponse toResponse(Message message) {
    List<LlmContent> content = new ArrayList<>();
    for (ContentBlock block : message.content()) {
      block.text().ifPresent(t -> content.add(new LlmContent.Text(t.text())));
      block
          .toolUse()
          .ifPresent(
              tu ->
                  content.add(new LlmContent.ToolUse(tu.id(), tu.name(), toJsonNode(tu._input()))));
    }
    String stopReason = message.stopReason().map(Object::toString).orElse(null);
    LlmUsage usage = new LlmUsage(message.usage().inputTokens(), message.usage().outputTokens());
    return new LlmResponse(content, stopReason, usage);
  }

  private JsonNode toJsonNode(JsonValue value) {
    return mapper.convertValue(value, JsonNode.class);
  }
}
