package com.schoolbridge.api.assistant.llm;

/**
 * Fallback {@link LlmGateway} used when the assistant is disabled (no SDK gateway bean). It exists
 * only so {@code AssistantServiceImpl} always has a dependency to wire; it is never invoked,
 * because the {@code /assistant} endpoints are themselves gated on {@code enabled=true}.
 */
public class DisabledLlmGateway implements LlmGateway {

  @Override
  public LlmResponse converse(LlmRequest request) {
    throw new IllegalStateException("Assistant is disabled (schoolbridge.assistant.enabled=false)");
  }
}

