package com.schoolbridge.api.assistant.llm;

import com.google.genai.Client;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Google GenAI client when the assistant is enabled and provider=gemini. Requires {@code
 * GEMINI_API_KEY} (Google AI Studio key) — validated at startup so startup fails fast rather than
 * at the first user request.
 */
@Configuration
@ConditionalOnExpression(
    "'${schoolbridge.assistant.provider:anthropic}'.equals('gemini')"
        + " and ${schoolbridge.assistant.enabled:false}"
        + " and '${schoolbridge.assistant.engine:native}'.equals('native')")
public class GeminiClientConfig {

  @Bean
  Client geminiClient(AssistantProperties properties) {
    String apiKey = properties.getGeminiApiKey();
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "schoolbridge.assistant.gemini-api-key (GEMINI_API_KEY) must be set when"
              + " provider=gemini and the assistant is enabled");
    }
    return Client.builder().apiKey(apiKey).build();
  }
}
