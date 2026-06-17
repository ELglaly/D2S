package com.schoolbridge.api.assistant.llm.springai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.llm.LlmContent;
import com.schoolbridge.api.assistant.llm.LlmMessage;
import com.schoolbridge.api.assistant.llm.LlmRequest;
import com.schoolbridge.api.assistant.llm.LlmResponse;
import com.schoolbridge.api.assistant.llm.LlmToolSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/** Maps the provider-neutral DTOs to/from Spring AI against a scripted {@link ChatModel} stub. */
class SpringAiLlmGatewayTest {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  private static JsonNode schema() {
    return JSON.createObjectNode().put("type", "object");
  }

  @Test
  void mapsTextResponseAndDisablesInternalToolExecution() {
    StubChatModel model =
        new StubChatModel(new ChatResponse(List.of(new Generation(new AssistantMessage("hello")))));
    SpringAiLlmGateway gateway = new SpringAiLlmGateway(model, JSON);

    LlmRequest request =
        new LlmRequest(
            "system prompt",
            List.of(LlmMessage.user("hi there")),
            List.of(new LlmToolSpec("mark_all_present", "mark all present", schema())),
            "claude-haiku",
            256);

    LlmResponse response = gateway.converse(request);

    assertThat(response.text()).isEqualTo("hello");
    assertThat(response.wantsTools()).isFalse();

    // Tools advertised, but Spring AI must never execute them (orchestrator owns the confirm gate).
    ToolCallingChatOptions options = (ToolCallingChatOptions) model.captured.getOptions();
    assertThat(options.getInternalToolExecutionEnabled()).isEqualTo(false);
    assertThat(options.getToolCallbacks()).hasSize(1);
    assertThat(options.getModel()).isEqualTo("claude-haiku");

    List<Message> sent = model.captured.getInstructions();
    assertThat(sent.get(0)).isInstanceOf(SystemMessage.class);
    assertThat(sent.get(0).getText()).isEqualTo("system prompt");
    assertThat(sent).anyMatch(m -> m instanceof UserMessage && "hi there".equals(m.getText()));
  }

  @Test
  void mapsToolCallResponseToToolUse() {
    AssistantMessage.ToolCall toolCall =
        new AssistantMessage.ToolCall("t1", "function", "mark_all_present", "{\"classId\":5}");
    ChatResponse withTool =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage("", Map.of(), List.of(toolCall)))));
    SpringAiLlmGateway gateway = new SpringAiLlmGateway(new StubChatModel(withTool), JSON);

    LlmResponse response =
        gateway.converse(new LlmRequest("s", List.of(LlmMessage.user("q")), List.of(), "m", 128));

    assertThat(response.wantsTools()).isTrue();
    assertThat(response.toolUses()).hasSize(1);
    LlmContent.ToolUse use = response.toolUses().get(0);
    assertThat(use.id()).isEqualTo("t1");
    assertThat(use.name()).isEqualTo("mark_all_present");
    assertThat(use.input().get("classId").asInt()).isEqualTo(5);
  }

  @Test
  void mapsAssistantToolUseAndUserToolResultHistory() {
    StubChatModel model =
        new StubChatModel(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
    SpringAiLlmGateway gateway = new SpringAiLlmGateway(model, JSON);

    LlmMessage assistantTurn =
        LlmMessage.assistant(
            List.of(new LlmContent.ToolUse("t1", "mark_all_present", JSON.createObjectNode())));
    LlmMessage toolResult =
        LlmMessage.user(List.of(new LlmContent.ToolResult("t1", "{\"ok\":true}", false)));

    gateway.converse(
        new LlmRequest(
            "s", List.of(LlmMessage.user("q"), assistantTurn, toolResult), List.of(), "m", 128));

    List<Message> sent = model.captured.getInstructions();
    AssistantMessage assistant =
        (AssistantMessage)
            sent.stream().filter(AssistantMessage.class::isInstance).findFirst().orElseThrow();
    assertThat(assistant.getToolCalls()).hasSize(1);
    assertThat(assistant.getToolCalls().get(0).name()).isEqualTo("mark_all_present");
    assertThat(sent).anyMatch(ToolResponseMessage.class::isInstance);
  }

  /** Records the last Prompt and replays a scripted ChatResponse. */
  private static final class StubChatModel implements ChatModel {

    private final ChatResponse response;
    private Prompt captured;

    StubChatModel(ChatResponse response) {
      this.response = response;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      this.captured = prompt;
      return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      this.captured = prompt;
      return Flux.just(response);
    }
  }
}
