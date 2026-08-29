package com.schoolbridge.api.assistant.llm;

import java.util.List;
import java.util.stream.Collectors;

/** A single model response: its content blocks, why it stopped, and token usage. */
public record LlmResponse(List<LlmContent> content, String stopReason, LlmUsage usage) {

  public List<LlmContent.ToolUse> toolUses() {
    return content.stream()
        .filter(LlmContent.ToolUse.class::isInstance)
        .map(LlmContent.ToolUse.class::cast)
        .toList();
  }

  public String text() {
    return content.stream()
        .filter(LlmContent.Text.class::isInstance)
        .map(c -> ((LlmContent.Text) c).text())
        .collect(Collectors.joining("\n"))
        .trim();
  }

  public boolean wantsTools() {
    return !toolUses().isEmpty();
  }
}

