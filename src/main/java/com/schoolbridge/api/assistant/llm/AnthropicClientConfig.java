package com.schoolbridge.api.assistant.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Anthropic SDK client when the assistant is enabled and provider=anthropic (default).
 * The SDK uses OkHttp (not the JDK {@code HttpClient}), so the Windows-JDK abort that bites
 * Spring's {@code RestClient} does not apply here. The API key is required and validated at startup
 * — fail fast rather than at first ask.
 */
@Configuration
@ConditionalOnExpression(
    "'${schoolbridge.assistant.provider:anthropic}'.equals('anthropic')"
        + " and ${schoolbridge.assistant.enabled:false}")
public class AnthropicClientConfig {

  @Bean
  AnthropicClient anthropicClient(AssistantProperties properties) {
    String apiKey = properties.getApiKey();
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "schoolbridge.assistant.api-key (ANTHROPIC_API_KEY) must be set when the assistant is"
              + " enabled");
    }
    return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
  }
}
