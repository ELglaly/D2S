package com.schoolbridge.api.assistant.llm;

import java.util.ArrayList;
import java.util.List;

/** Helpers for the non-streaming fallback path: splits a finished answer into small deltas. */
public final class StreamText {

  private static final int CHUNK = 12;

  private StreamText() {}

  /**
   * Splits {@code text} into fixed-size pieces whose concatenation reproduces the original exactly.
   * Used by {@link LlmGateway#converseStreaming} default so providers without a native stream still
   * emit incremental deltas.
   */
  public static List<String> chunk(String text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (int i = 0; i < text.length(); i += CHUNK) {
      out.add(text.substring(i, Math.min(i + CHUNK, text.length())));
    }
    return out;
  }
}

