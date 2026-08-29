package com.schoolbridge.api.assistant.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ChatStream} that writes Anthropic-style SSE frames straight to the servlet response
 * writer. Writing raw frames (rather than returning an object) keeps the Jackson-based {@code
 * ApiResponseBodyAdvice} from wrapping them. A failed flush ({@link PrintWriter#checkError()})
 * means the client disconnected, which flips {@link #isCancelled()} so the orchestrator aborts and
 * the LLM call is closed.
 */
final class SseChatStream implements ChatStream {

  private final PrintWriter writer;
  private final ObjectMapper mapper;
  private boolean cancelled;

  SseChatStream(PrintWriter writer, ObjectMapper mapper) {
    this.writer = writer;
    this.mapper = mapper;
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public void messageStart(String messageId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("message_id", messageId);
    emit("message_start", data);
  }

  @Override
  public void retrievalStarted() {
    emit("retrieval_started", new LinkedHashMap<>());
  }

  @Override
  public void retrievalCompleted(int matches) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("matches", matches);
    emit("retrieval_completed", data);
  }

  @Override
  public void contentBlockStart(int index) {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("type", "text");
    block.put("text", "");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("index", index);
    data.put("content_block", block);
    emit("content_block_start", data);
  }

  @Override
  public void contentBlockDelta(int index, String text) {
    Map<String, Object> delta = new LinkedHashMap<>();
    delta.put("type", "text_delta");
    delta.put("text", text);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("index", index);
    data.put("delta", delta);
    emit("content_block_delta", data);
  }

  @Override
  public void contentBlockStop(int index) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("index", index);
    emit("content_block_stop", data);
  }

  @Override
  public void messageStop(String stopReason, String pendingActionToken) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("stop_reason", stopReason);
    if (pendingActionToken != null) {
      data.put("pending_action_token", pendingActionToken);
    }
    emit("message_stop", data);
  }

  @Override
  public void error(String message) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("message", message == null ? "" : message);
    emit("error", data);
  }

  private void emit(String event, Map<String, Object> data) {
    if (cancelled) {
      return;
    }
    try {
      writer.write("event: " + event + "\n");
      writer.write("data: " + mapper.writeValueAsString(data) + "\n\n");
      writer.flush();
      if (writer.checkError()) {
        cancelled = true;
      }
    } catch (JsonProcessingException e) {
      cancelled = true;
    }
  }
}

