package com.schoolbridge.api.assistant.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires a {@link RestClient} for the DeepSeek chat-completions API (OpenAI-compatible) when the
 * assistant is enabled and provider=deepseek. DeepSeek ships no first-party Java SDK, so — exactly
 * like the WhatsApp cloud adapter — this uses Spring's {@code RestClient} with {@link
 * SimpleClientHttpRequestFactory}; the default JDK {@code HttpClient} aborts on Windows JDK. The
 * API key is required and validated at startup so the context fails fast rather than at the first
 * ask.
 */
@Configuration
@ConditionalOnExpression(
    "'${schoolbridge.assistant.provider:anthropic}'.equals('deepseek')"
        + " and ${schoolbridge.assistant.enabled:false}"
        + " and '${schoolbridge.assistant.engine:native}'.equals('native')")
public class DeepSeekClientConfig {

  @Bean
  RestClient deepSeekRestClient(RestClient.Builder builder, AssistantProperties properties) {
    String apiKey = properties.getDeepseekApiKey();
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "schoolbridge.assistant.deepseek-api-key (DEEPSEEK_API_KEY) must be set when"
              + " provider=deepseek and the assistant is enabled");
    }
    int timeoutMillis =
        (int) Math.min(properties.getRequestTimeout().toMillis(), Integer.MAX_VALUE);
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeoutMillis);
    factory.setReadTimeout(timeoutMillis);
    // No baseUrl: DeepSeekLlmGateway posts to an absolute URL built from deepseek-base-url, so a
    // base path such as NVIDIA NIM's "/v1" is preserved instead of being replaced by the request
    // path (DefaultUriBuilderFactory's leading-slash behaviour).
    return builder
        .requestFactory(factory)
        .defaultHeader("Authorization", "Bearer " + apiKey)
        .build();
  }
}
