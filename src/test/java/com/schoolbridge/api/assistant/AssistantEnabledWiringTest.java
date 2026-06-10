package com.schoolbridge.api.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.assistant.llm.LlmContent;
import com.schoolbridge.api.assistant.llm.LlmGateway;
import com.schoolbridge.api.assistant.llm.LlmResponse;
import com.schoolbridge.api.assistant.llm.LlmUsage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Verifies the dark→enabled transition wires cleanly: with {@code enabled=true} (and a fake key)
 * the controller, action service, and SDK-gated beans all load, while a {@code @Primary} stub
 * gateway keeps the real Anthropic client off the test path. The default profile (enabled=false) is
 * covered by every other {@code @SpringBootTest}, which boots without any LLM bean.
 */
@SpringBootTest(
    properties = {
      "schoolbridge.assistant.enabled=true",
      "schoolbridge.assistant.api-key=test-key",
      "schoolbridge.assistant.actions.enabled=true"
    })
class AssistantEnabledWiringTest extends AbstractIntegrationTest {

  @TestConfiguration
  static class StubGatewayConfig {
    @Bean
    @Primary
    LlmGateway stubGateway() {
      return request ->
          new LlmResponse(List.of(new LlmContent.Text("ok")), "end_turn", LlmUsage.zero());
    }
  }

  @Autowired ApplicationContext context;

  @Test
  void assistantBeansAreWiredWhenEnabled() {
    assertThat(context.getBean(AssistantController.class)).isNotNull();
    assertThat(context.getBean(AssistantService.class)).isNotNull();
    assertThat(context.getBean(AssistantActionService.class)).isNotNull();
    assertThat(context.getBean(AssistantRateLimiter.class)).isNotNull();
    // The @Primary stub resolves the LlmGateway even though the real SDK gateway also loads.
    LlmGateway gateway = context.getBean(LlmGateway.class);
    assertThat(gateway).isNotNull();
    assertThat(gateway.converse(null).text()).isEqualTo("ok");
  }
}
