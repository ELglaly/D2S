package com.schoolbridge.api.assistant.llm.springai;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.assistant.llm.AnthropicLlmGateway;
import com.schoolbridge.api.assistant.llm.DeepSeekLlmGateway;
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
 * With {@code engine=springai} the single {@link LlmGateway} bean is the {@link SpringAiLlmGateway}
 * (backed here by a stub {@link ChatModel}) and the native provider gateways are switched off — the
 * mutual exclusion that lets the migration ship dark and flip by config.
 */
@SpringBootTest(
    properties = {"schoolbridge.assistant.enabled=true", "schoolbridge.assistant.engine=springai"})
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
  void springAiGatewaySelectedAndNativeGatewaysOff() {
    LlmGateway gateway = context.getBean(LlmGateway.class);
    assertThat(gateway).isInstanceOf(SpringAiLlmGateway.class);
    assertThat(context.getBeanNamesForType(AnthropicLlmGateway.class)).isEmpty();
    assertThat(context.getBeanNamesForType(DeepSeekLlmGateway.class)).isEmpty();

    LlmRequest request =
        new LlmRequest("system", List.of(LlmMessage.user("hi")), List.of(), "model", 64);
    assertThat(gateway.converse(request).text()).isEqualTo("ok");
  }
}
