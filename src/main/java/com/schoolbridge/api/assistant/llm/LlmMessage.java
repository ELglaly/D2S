package com.schoolbridge.api.assistant.llm;

import java.util.List;

/** A single conversation message: a role plus its ordered content blocks. */
public record LlmMessage(Role role, List<LlmContent> content) {

  public enum Role {
    USER,
    ASSISTANT
  }

  public static LlmMessage user(String text) {
    return new LlmMessage(Role.USER, List.of(new LlmContent.Text(text)));
  }

  public static LlmMessage user(List<LlmContent> content) {
    return new LlmMessage(Role.USER, content);
  }

  public static LlmMessage assistant(List<LlmContent> content) {
    return new LlmMessage(Role.ASSISTANT, content);
  }
}
