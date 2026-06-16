package com.schoolbridge.api.assistant.conversation;

/**
 * Sink for the Anthropic-style SSE frames of one streamed reply. The HTTP controller backs this
 * with the servlet response; tests back it with a recorder. {@link #isCancelled()} reports a client
 * disconnect so the orchestrator can stop and the gateway can abort the LLM call.
 */
public interface ChatStream {

  /** True once the client has disconnected (a write failed). */
  boolean isCancelled();

  void messageStart(String messageId);

  void contentBlockStart(int index);

  void contentBlockDelta(int index, String text);

  void contentBlockStop(int index);

  /** Terminal frame. {@code pendingActionToken} is non-null only when a confirmation is awaited. */
  void messageStop(String stopReason, String pendingActionToken);

  void error(String message);
}
