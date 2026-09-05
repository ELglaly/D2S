package com.schoolbridge.api.assistant.llm.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.llm.LlmContent;
import com.schoolbridge.api.assistant.llm.LlmGateway;
import com.schoolbridge.api.assistant.llm.LlmMessage;
import com.schoolbridge.api.assistant.llm.LlmRequest;
import com.schoolbridge.api.assistant.llm.LlmResponse;
import com.schoolbridge.api.assistant.llm.LlmToolSpec;
import com.schoolbridge.api.assistant.llm.LlmUsage;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link LlmGateway} backed by a Spring AI {@link ChatModel} â€” the only real gateway in the
 * codebase (ADR-007). Loaded when {@code schoolbridge.assistant.enabled=true}; otherwise {@code
 * AssistantConfig} supplies {@code DisabledLlmGateway}. The concrete provider behind the {@code
 * ChatModel} is selected by Spring AI auto-config via {@code spring.ai.model.chat} + the provider's
 * api-key.
 *
 * <p><b>Tool execution stays external.</b> Tools are advertised to the model as {@link
 * ToolCallback} definitions, but {@code internalToolExecutionEnabled=false} stops Spring AI from
 * ever invoking them â€” the model's tool-call requests are returned in the {@link ChatResponse}
 * and handed back to the existing orchestrator, which keeps the role/permission checks and the
 * previewâ†’confirmâ†’execute gate intact. The advertised callbacks therefore throw if called.
 *
 * <p>Streaming uses the {@link LlmGateway#converseStreaming default} chunked implementation for now
 * (one blocking turn, then text chunked to the SSE sink); true token streaming over {@code
 * ChatModel.stream} is a later enhancement and is not needed for functional parity.
 */
@Component
@ConditionalOnExpression("${schoolbridge.assistant.enabled:false}")
public class SpringAiLlmGateway implements LlmGateway {

  private final ChatModel chatModel;
  private final ObjectMapper mapper;

  public SpringAiLlmGateway(ChatModel chatModel, ObjectMapper mapper) {
    this.chatModel = chatModel;
    this.mapper = mapper;
  }

  @Override
  @CircuitBreaker(name = "assistant")
  public LlmResponse converse(LlmRequest request) {
    return toLlmResponse(chatModel.call(toPrompt(request)));
  }

  // --- request mapping ------------------------------------------------------

  private Prompt toPrompt(LlmRequest request) {
    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(request.system()));
    for (LlmMessage message : request.messages()) {
      messages.addAll(toMessages(message));
    }
    ToolCallingChatOptions options =
        ToolCallingChatOptions.builder()
            .model(request.model())
            .maxTokens((int) request.maxTokens())
            // Orchestrator owns execution â†’ never let Spring AI run a tool (preserves confirm
            // gate).
            .internalToolExecutionEnabled(false)
            .toolCallbacks(toToolCallbacks(request.tools()))
            .build();
    return new Prompt(messages, options);
  }

  /**
   * One internal message â†’ one or more Spring AI messages (mirrors the OpenAI/DeepSeek fan-out).
   */
  private List<Message> toMessages(LlmMessage message) {
    if (message.role() == LlmMessage.Role.ASSISTANT) {
      return List.of(toAssistantMessage(message));
    }
    List<Message> out = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
    for (LlmContent content : message.content()) {
      if (content instanceof LlmContent.Text t) {
        if (text.length() > 0) {
          text.append('\n');
        }
        text.append(t.text());
      } else if (content instanceof LlmContent.ToolResult result) {
        toolResponses.add(
            new ToolResponseMessage.ToolResponse(result.toolUseId(), "tool", result.content()));
      }
    }
    if (!toolResponses.isEmpty()) {
      out.add(new ToolResponseMessage(toolResponses));
    }
    if (text.length() > 0) {
      out.add(new UserMessage(text.toString()));
    }
    return out;
  }

  private AssistantMessage toAssistantMessage(LlmMessage message) {
    StringBuilder text = new StringBuilder();
    List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
    for (LlmContent content : message.content()) {
      if (content instanceof LlmContent.Text t) {
        if (text.length() > 0) {
          text.append('\n');
        }
        text.append(t.text());
      } else if (content instanceof LlmContent.ToolUse toolUse) {
        toolCalls.add(
            new AssistantMessage.ToolCall(
                toolUse.id(), "function", toolUse.name(), argumentsJson(toolUse.input())));
      }
    }
    return new AssistantMessage(text.toString(), Map.of(), toolCalls);
  }

  private List<ToolCallback> toToolCallbacks(List<LlmToolSpec> specs) {
    List<ToolCallback> callbacks = new ArrayList<>(specs.size());
    for (LlmToolSpec spec : specs) {
      ToolDefinition definition =
          ToolDefinition.builder()
              .name(spec.name())
              .description(spec.description())
              .inputSchema(spec.inputSchema() == null ? "{}" : spec.inputSchema().toString())
              .build();
      callbacks.add(new AdvertisedToolCallback(definition));
    }
    return callbacks;
  }

  private String argumentsJson(JsonNode input) {
    return input == null || input.isNull() ? "{}" : input.toString();
  }

  // --- response mapping -----------------------------------------------------

  private LlmResponse toLlmResponse(ChatResponse response) {
    List<LlmContent> content = new ArrayList<>();
    String stopReason = "end_turn";
    if (response != null && response.getResults() != null) {
      for (Generation generation : response.getResults()) {
        AssistantMessage output = generation.getOutput();
        if (output != null) {
          String text = output.getText();
          if (text != null && !text.isBlank()) {
            content.add(new LlmContent.Text(text));
          }
          if (output.getToolCalls() != null) {
            for (AssistantMessage.ToolCall call : output.getToolCalls()) {
              content.add(
                  new LlmContent.ToolUse(call.id(), call.name(), parseArguments(call.arguments())));
            }
          }
        }
        if (generation.getMetadata() != null) {
          String finishReason = generation.getMetadata().getFinishReason();
          if (finishReason != null && !finishReason.isBlank()) {
            stopReason = finishReason;
          }
        }
      }
    }
    return new LlmResponse(content, stopReason, toUsage(response));
  }

  private LlmUsage toUsage(ChatResponse response) {
    if (response == null || response.getMetadata() == null) {
      return LlmUsage.zero();
    }
    Usage usage = response.getMetadata().getUsage();
    if (usage == null) {
      return LlmUsage.zero();
    }
    Number prompt = usage.getPromptTokens();
    Number completion = usage.getCompletionTokens();
    return new LlmUsage(
        prompt == null ? 0 : prompt.longValue(), completion == null ? 0 : completion.longValue());
  }

  private JsonNode parseArguments(String arguments) {
    if (arguments == null || arguments.isBlank()) {
      return mapper.createObjectNode();
    }
    try {
      return mapper.readTree(arguments);
    } catch (JsonProcessingException e) {
      return mapper.createObjectNode();
    }
  }

  /**
   * A tool advertised to the model for selection only. Spring AI never invokes it because {@code
   * internalToolExecutionEnabled=false}; the orchestrator executes the chosen tool through the
   * existing authorization + confirmation path. Calling it would be a bug, so it fails loudly.
   */
  private static final class AdvertisedToolCallback implements ToolCallback {

    private final ToolDefinition definition;

    AdvertisedToolCallback(ToolDefinition definition) {
      this.definition = definition;
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return definition;
    }

    @Override
    public String call(String toolInput) {
      throw new UnsupportedOperationException(
          "Assistant tools are executed by the orchestrator, never by Spring AI");
    }
  }
}
