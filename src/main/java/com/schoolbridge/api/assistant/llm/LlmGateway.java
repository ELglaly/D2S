package com.schoolbridge.api.assistant.llm;

/**
 * Boundary between the orchestrator and the LLM provider. One call = one model turn. Keeping this
 * an interface lets the read/action orchestration and all tests run against a scripted stub with no
 * SDK dependency; {@code AnthropicLlmGateway} is the only implementation that touches the network
 * and is loaded only when {@code schoolbridge.assistant.enabled=true}.
 */
public interface LlmGateway {

  LlmResponse converse(LlmRequest request);
}
