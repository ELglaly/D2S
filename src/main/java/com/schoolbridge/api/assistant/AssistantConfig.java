package com.schoolbridge.api.assistant;

import com.schoolbridge.api.assistant.llm.DisabledLlmGateway;
import com.schoolbridge.api.assistant.llm.LlmGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Assistant module wiring that must always be present, regardless of the enabled flag. */
@Configuration
public class AssistantConfig {

  /**
   * Guarantees exactly one {@link LlmGateway} bean: {@code SpringAiLlmGateway} when the assistant
   * is enabled, otherwise this no-op fallback so the orchestrator still wires cleanly.
   */
  @Bean
  @ConditionalOnMissingBean(LlmGateway.class)
  LlmGateway disabledLlmGateway() {
    return new DisabledLlmGateway();
  }
}
