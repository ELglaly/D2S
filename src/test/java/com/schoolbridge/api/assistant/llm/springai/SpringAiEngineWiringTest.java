package com.schoolbridge.api.assistant.llm.springai;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.assistant.llm.LlmGateway;
import com.schoolbridge.api.assistant.llm.LlmMessage;
import com.schoolbridge.api.assistant.llm.LlmRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

/**
 * When the assistant is enabled, the single {@link LlmGateway} bean is {@link SpringAiLlmGateway}
 * (backed here by a stub {@link ChatModel}). Spring AI is the only engine — the native provider
 * gateways were deleted in ADR-007, so there is no {@code engine} property to select and no second
 * implementation that could win the bean.
 */
@SpringBootTest(
    properties = {
      "schoolbridge.assistant.enabled=true",
      "spring.ai.model.chat=openai",
      "spring.ai.openai.api-key=test-key"
    })
class SpringAiEngineWiringTest extends AbstractIntegrationTest {

  @TestConfiguration
  static class StubChatModelConfig {
    @Bean
    @Primary
    ChatModel stubChatModel() {
      return new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
          return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
          return Flux.just(call(prompt));
        }
      };
    }
  }

  @Autowired ApplicationContext context;

  @Test
  void springAiIsTheOnlyGateway() {
    LlmGateway gateway = context.getBean(LlmGateway.class);
    assertThat(gateway).isInstanceOf(SpringAiLlmGateway.class);
    // Exactly one gateway bean — the DisabledLlmGateway fallback must not also be present.
    assertThat(context.getBeanNamesForType(LlmGateway.class)).hasSize(1);

    LlmRequest request =
        new LlmRequest("system", List.of(LlmMessage.user("hi")), List.of(), "model", 64);
    assertThat(gateway.converse(request).text()).isEqualTo("ok");
  }
}
