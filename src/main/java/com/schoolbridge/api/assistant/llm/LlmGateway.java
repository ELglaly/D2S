package com.schoolbridge.api.assistant.llm;

/**
 * Boundary between the orchestrator and the LLM provider. One call = one model turn. Keeping this
 * an interface lets the read/action orchestration and all tests run against a scripted stub with no
 * SDK dependency; {@code SpringAiLlmGateway} is the only implementation that touches the network
 * and is loaded only when {@code schoolbridge.assistant.enabled=true}.
 */
public interface LlmGateway {

  LlmResponse converse(LlmRequest request);

  /**
   * Streams a single model turn, forwarding each text delta to {@code listener} and aborting as
   * soon as {@code cancelled} reports true (the client disconnected). Returns the aggregated
   * response so the orchestrator can still see tool-use and usage. The default implementation falls
   * back to a blocking {@link #converse} followed by chunked deltas, which is what {@code
   * SpringAiLlmGateway} currently uses; true token-by-token streaming over {@code ChatModel.stream}
   * is a later enhancement.
   */
  default LlmResponse converseStreaming(
      LlmRequest request,
      LlmStreamListener listener,
      java.util.function.BooleanSupplier cancelled) {
    LlmResponse response = converse(request);
    for (String chunk : StreamText.chunk(response.text())) {
      if (cancelled.getAsBoolean()) {
        break;
      }
      listener.onTextDelta(chunk);
    }
    return response;
  }
}
