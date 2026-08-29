package com.schoolbridge.api.assistant.llm;

import java.util.List;

/**
 * A single model turn: system prompt, full conversation so far, available tools, model + budget.
 */
public record LlmRequest(
    String system,
    List<LlmMessage> messages,
    List<LlmToolSpec> tools,
    String model,
    long maxTokens) {}

