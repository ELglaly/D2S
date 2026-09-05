package com.schoolbridge.api.assistant.llm;

/**
 * Receives incremental text as the model streams a single turn. The orchestrator implements this to
 * forward each delta to the SSE response. Tool-use and other non-text content is not streamed â€”
 * it is returned in the aggregated {@link LlmResponse} when the turn completes.
 */
@FunctionalInterface
public interface LlmStreamListener {

  void onTextDelta(String text);
}
