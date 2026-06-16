package com.schoolbridge.api.assistant.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link LlmGateway} backed by the Anthropic Messages API. Loaded only when {@code
 * schoolbridge.assistant.enabled=true} and {@code provider=anthropic} (the default).
 */
@Component
@ConditionalOnExpression(
    "'${schoolbridge.assistant.provider:anthropic}'.equals('anthropic')"
        + " and ${schoolbridge.assistant.enabled:false}")
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
    return toResponse(client.messages().create(buildParams(request)));
  }

  /**
   * True token-by-token stream. Forwards each text delta to {@code listener} and, the moment {@code
   * cancelled} reports a client disconnect, closes the {@link StreamResponse} — which aborts the
   * underlying HTTP call to Anthropic. The final {@link LlmResponse} is reassembled from the stream
   * via {@link MessageAccumulator} so tool-use and usage survive.
   */
  @Override
  @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "assistant")
  public LlmResponse converseStreaming(
      LlmRequest request,
      LlmStreamListener listener,
      java.util.function.BooleanSupplier cancelled) {
    MessageAccumulator accumulator = MessageAccumulator.create();
    boolean aborted = false;
    try (StreamResponse<RawMessageStreamEvent> stream =
        client.messages().createStreaming(buildParams(request))) {
      Iterator<RawMessageStreamEvent> events = stream.stream().iterator();
      while (events.hasNext()) {
        if (cancelled.getAsBoolean()) {
          aborted = true;
          break;
        }
        RawMessageStreamEvent event = events.next();
        accumulator.accumulate(event);
        event
            .contentBlockDelta()
            .map(RawContentBlockDeltaEvent::delta)
            .flatMap(RawContentBlockDelta::text)
            .ifPresent(text -> listener.onTextDelta(text.text()));
      }
    }
    if (aborted) {
      return new LlmResponse(List.of(), "cancelled", LlmUsage.zero());
    }
    return toResponse(accumulator.message());
  }

  private MessageCreateParams buildParams(LlmRequest request) {
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
    return builder.build();
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
