package com.schoolbridge.api.assistant.llm;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fails the context at startup when the assistant is switched on without a usable provider, rather
 * than letting the app boot and 500 on the first question.
 *
 * <p>This replaces the per-provider validation that used to live in the deleted {@code
 * *ClientConfig} classes. Spring AI is the only engine now (ADR-007), so the credential lives at
 * {@code spring.ai.openai.api-key} and there is exactly one thing to check.
 *
 * <p>Deliberately loaded only when {@code schoolbridge.assistant.enabled=true}: a disabled
 * assistant must never require a key, which is what makes shipping dark cost nothing operationally.
 */
@Component
@ConditionalOnProperty(name = "schoolbridge.assistant.enabled", havingValue = "true")
public class AssistantStartupValidator {

  private final String chatProvider;
  private final String openAiApiKey;

  public AssistantStartupValidator(
      @Value("${spring.ai.model.chat:none}") String chatProvider,
      @Value("${spring.ai.openai.api-key:}") String openAiApiKey) {
    this.chatProvider = chatProvider;
    this.openAiApiKey = openAiApiKey;
  }

  @PostConstruct
  void validate() {
    if ("none".equalsIgnoreCase(chatProvider)) {
      throw new IllegalStateException(
          "schoolbridge.assistant.enabled=true but spring.ai.model.chat=none â€” no chat provider is"
              + " configured. Set SPRING_AI_CHAT to a provider (e.g. openai) and supply its API"
              + " key, or set ASSISTANT_ENABLED=false.");
    }
    if ("openai".equalsIgnoreCase(chatProvider) && openAiApiKey.isBlank()) {
      throw new IllegalStateException(
          "schoolbridge.assistant.enabled=true and spring.ai.model.chat=openai but"
              + " spring.ai.openai.api-key is blank. Set OPENAI_API_KEY in the environment; it has"
              + " no default and must never be committed to application.yml.");
    }
  }
}

