package com.schoolbridge.api.assistant.llm;

/** Token accounting for one model turn. */
public record LlmUsage(long inputTokens, long outputTokens) {

  public static LlmUsage zero() {
    return new LlmUsage(0, 0);
  }

  public LlmUsage plus(LlmUsage other) {
    return new LlmUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
  }
}

