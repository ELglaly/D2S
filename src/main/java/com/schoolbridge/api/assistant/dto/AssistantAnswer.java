package com.schoolbridge.api.assistant.dto;

import java.util.Map;

/**
 * Outcome of an {@code /ask}: either a final answer, a confirmation request for a proposed action,
 * or an error. {@code pendingAction} is non-null only when {@code outcome == CONFIRM_REQUIRED}.
 */
public record AssistantAnswer(
    Outcome outcome, String text, ActionPreview pendingAction, Map<String, Object> metadata) {

  public enum Outcome {
    ANSWERED,
    CONFIRM_REQUIRED,
    ERROR
  }

  public static AssistantAnswer answered(String text, Map<String, Object> metadata) {
    return new AssistantAnswer(Outcome.ANSWERED, text, null, metadata);
  }

  public static AssistantAnswer error(String text, Map<String, Object> metadata) {
    return new AssistantAnswer(Outcome.ERROR, text, null, metadata);
  }

  public static AssistantAnswer confirmRequired(
      ActionPreview pendingAction, String text, Map<String, Object> metadata) {
    return new AssistantAnswer(Outcome.CONFIRM_REQUIRED, text, pendingAction, metadata);
  }
}
