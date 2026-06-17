package com.schoolbridge.api.assistant.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.AssistantActionService;
import com.schoolbridge.api.assistant.confirm.ConfirmIntent;
import com.schoolbridge.api.assistant.dto.ActionPreview;
import com.schoolbridge.api.assistant.dto.ConfirmActionRequest;
import com.schoolbridge.api.assistant.dto.ConfirmResult;
import com.schoolbridge.api.assistant.llm.AssistantProperties;
import com.schoolbridge.api.assistant.llm.LlmContent;
import com.schoolbridge.api.assistant.llm.LlmGateway;
import com.schoolbridge.api.assistant.llm.LlmMessage;
import com.schoolbridge.api.assistant.llm.LlmRequest;
import com.schoolbridge.api.assistant.llm.LlmResponse;
import com.schoolbridge.api.assistant.llm.LlmStreamListener;
import com.schoolbridge.api.assistant.llm.LlmToolSpec;
import com.schoolbridge.api.assistant.llm.LlmUsage;
import com.schoolbridge.api.assistant.llm.StreamText;
import com.schoolbridge.api.assistant.rag.ContextAugmenter;
import com.schoolbridge.api.assistant.rag.RagRetriever;
import com.schoolbridge.api.assistant.rag.RetrievedChunk;
import com.schoolbridge.api.assistant.settings.AssistantSettingsService;
import com.schoolbridge.api.assistant.tools.ActionTool;
import com.schoolbridge.api.assistant.tools.PreviewOutcome;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.Tool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolRegistry;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.common.i18n.MessageResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Streams an assistant reply into a {@link ChatStream}, running the same role-scoped read-tool loop
 * and confirm-then-execute gate as {@code /ask}, but token-by-token. Runs on the request thread so
 * the tenant and security thread-locals stay valid; it is intentionally NOT {@code @Transactional}
 * (persistence is delegated to {@link ConversationStore}'s short transactions).
 *
 * <p>The user turn is persisted immediately; the assistant turn only after the stream completes
 * successfully. A CONFIRM/CANCEL reply against a pending proposal is routed to the existing action
 * machinery before a fresh reply is streamed.
 */
@Service
public class ConversationChatService {

  private static final Logger log = LoggerFactory.getLogger(ConversationChatService.class);

  private final ConversationService conversations;
  private final ConversationStore store;
  private final LlmGateway gateway;
  private final ToolRegistry registry;
  private final AssistantSettingsService settings;
  private final AssistantProperties properties;
  private final AssistantActionService actionService;
  private final MessageResolver messages;
  private final ObjectMapper mapper;
  private final RagRetriever retriever;
  private final ContextAugmenter augmenter;

  public ConversationChatService(
      ConversationService conversations,
      ConversationStore store,
      LlmGateway gateway,
      ToolRegistry registry,
      AssistantSettingsService settings,
      AssistantProperties properties,
      AssistantActionService actionService,
      MessageResolver messages,
      ObjectMapper mapper,
      RagRetriever retriever,
      ContextAugmenter augmenter) {
    this.conversations = conversations;
    this.store = store;
    this.gateway = gateway;
    this.registry = registry;
    this.settings = settings;
    this.properties = properties;
    this.actionService = actionService;
    this.messages = messages;
    this.mapper = mapper;
    this.retriever = retriever;
    this.augmenter = augmenter;
  }

  /** Entry point: validates, routes CONFIRM/CANCEL, otherwise streams a fresh agentic reply. */
  public void stream(ToolContext ctx, UUID conversationId, String content, ChatStream sink) {
    Conversation conversation =
        conversations.requireOwned(ctx.schoolId(), ctx.userId(), conversationId);
    String trimmed = content == null ? "" : content.trim();
    if (trimmed.isEmpty()) {
      // Defensive: @NotBlank on SendMessageRequest already rejects this with a 422 before
      // streaming.
      sink.error(messages.get("assistant.error.empty"));
      return;
    }

    if (ConfirmIntent.classify(trimmed) != ConfirmIntent.Decision.UNKNOWN) {
      Optional<String> pending = store.latestPendingToken(conversationId);
      if (pending.isPresent()) {
        handleConfirmation(ctx, conversation, trimmed, pending.get(), sink);
        return;
      }
    }

    store.appendUser(ctx.schoolId(), conversationId, trimmed);
    try {
      streamAnswer(ctx, conversation, trimmed, sink);
    } catch (RuntimeException e) {
      log.warn("Assistant stream failed for conversation {}", conversationId, e);
      sink.error(messages.get("assistant.error.stream_failed"));
    }
  }

  /**
   * Routes a CONFIRM/CANCEL reply to the action machinery, then streams the deterministic result.
   */
  private void handleConfirmation(
      ToolContext ctx, Conversation conversation, String reply, String token, ChatStream sink) {
    store.appendUser(ctx.schoolId(), conversation.getId(), reply);
    ConfirmResult result =
        ConfirmIntent.classify(reply) == ConfirmIntent.Decision.CONFIRM
            ? actionService.confirm(token, new ConfirmActionRequest(reply), ctx)
            : actionService.cancel(token, ctx);
    String text =
        result.message() != null ? result.message() : messages.get("assistant.action.executed");
    streamWholeMessage(sink, text, "end_turn", null);
    if (!sink.isCancelled()) {
      store.appendAssistant(ctx.schoolId(), conversation.getId(), text, null, null, null);
    }
  }

  /** The streaming read/action loop. Final assistant turn is persisted only on clean completion. */
  private void streamAnswer(
      ToolContext ctx, Conversation conversation, String query, ChatStream sink) {
    List<LlmToolSpec> tools =
        registry.toolsFor(ctx).stream()
            .map(t -> new LlmToolSpec(t.name(), t.description(), t.inputSchema()))
            .toList();
    String system = settings.resolveSystemPrompt(ctx);
    List<LlmMessage> history =
        store.loadHistory(conversation.getId(), properties.getMaxHistoryMessages());

    sink.messageStart(newMessageId());

    if (properties.getRag().isEnabled()) {
      sink.retrievalStarted();
      List<RetrievedChunk> chunks = retriever.retrieve(query, ctx);
      sink.retrievalCompleted(chunks.size());
      system = augmenter.augment(system, chunks);
    }

    LlmUsage usage = LlmUsage.zero();
    int blockIndex = 0;
    int iterations = 0;
    while (iterations < properties.getMaxToolIterations()) {
      iterations++;
      if (sink.isCancelled()) {
        return;
      }
      TurnStreamer turn = new TurnStreamer(sink, blockIndex);
      LlmResponse response =
          gateway.converseStreaming(
              new LlmRequest(
                  system, history, tools, properties.getModel(), properties.getMaxTokens()),
              turn,
              sink::isCancelled);
      usage = usage.plus(response.usage());
      if (turn.finish()) {
        blockIndex++;
      }
      if (sink.isCancelled()) {
        // Client disconnected mid-turn: abort with no assistant row (the LLM call was closed).
        return;
      }

      if (!response.wantsTools()) {
        sink.messageStop("end_turn", null);
        store.appendAssistant(
            ctx.schoolId(),
            conversation.getId(),
            response.text(),
            null,
            usage.inputTokens(),
            usage.outputTokens());
        return;
      }

      history.add(LlmMessage.assistant(response.content()));
      List<LlmContent> toolResults = new ArrayList<>();
      for (LlmContent.ToolUse call : response.toolUses()) {
        Optional<Tool> found = registry.find(call.name(), ctx);
        if (found.isPresent() && found.get() instanceof ActionTool action) {
          PreviewOutcome outcome = previewSafely(action, call, ctx);
          if (outcome instanceof PreviewOutcome.Prepared prepared) {
            confirmRequired(ctx, conversation, prepared.preview(), sink, blockIndex, usage);
            return;
          }
          toolResults.add(toolResult(call.id(), ((PreviewOutcome.Rejected) outcome).result()));
        } else {
          toolResults.add(invokeRead(call, found, ctx));
        }
      }
      history.add(LlmMessage.user(toolResults));
    }

    String fallback = messages.get("assistant.error.max_iterations");
    streamInlineBlock(sink, blockIndex, fallback);
    sink.messageStop("end_turn", null);
    if (!sink.isCancelled()) {
      store.appendAssistant(
          ctx.schoolId(),
          conversation.getId(),
          fallback,
          null,
          usage.inputTokens(),
          usage.outputTokens());
    }
  }

  /** Halts the loop on a proposed mutation: streams the question, stores the pending token. */
  private void confirmRequired(
      ToolContext ctx,
      Conversation conversation,
      ActionPreview preview,
      ChatStream sink,
      int blockIndex,
      LlmUsage usage) {
    String summary =
        "ar".equals(ctx.language().getLanguage()) ? preview.summaryAr() : preview.summaryEn();
    streamInlineBlock(sink, blockIndex, summary);
    sink.messageStop("confirmation_required", preview.token());
    if (!sink.isCancelled()) {
      store.appendAssistant(
          ctx.schoolId(),
          conversation.getId(),
          summary,
          preview.token(),
          usage.inputTokens(),
          usage.outputTokens());
    }
  }

  // --- streaming helpers ----------------------------------------------------

  /** A whole message (start → one text block → stop) for deterministic, non-model replies. */
  private void streamWholeMessage(ChatStream sink, String text, String stopReason, String token) {
    sink.messageStart(newMessageId());
    streamInlineBlock(sink, 0, text);
    sink.messageStop(stopReason, token);
  }

  /** Emits one complete text content block, chunked so the client still sees incremental deltas. */
  private void streamInlineBlock(ChatStream sink, int index, String text) {
    if (sink.isCancelled() || text == null || text.isEmpty()) {
      return;
    }
    sink.contentBlockStart(index);
    for (String chunk : StreamText.chunk(text)) {
      if (sink.isCancelled()) {
        return;
      }
      sink.contentBlockDelta(index, chunk);
    }
    sink.contentBlockStop(index);
  }

  private static String newMessageId() {
    return "msg_" + UUID.randomUUID().toString().replace("-", "");
  }

  // --- tool helpers (mirror AssistantServiceImpl) ---------------------------

  private LlmContent.ToolResult invokeRead(
      LlmContent.ToolUse call, Optional<Tool> found, ToolContext ctx) {
    if (found.isEmpty() || !(found.get() instanceof ReadTool tool)) {
      return toolResult(call.id(), ToolResult.error(messages.get("assistant.error.unknown_tool")));
    }
    JsonNode input = call.input() == null ? mapper.createObjectNode() : call.input();
    ToolResult result;
    try {
      result = tool.execute(input, ctx);
    } catch (RuntimeException e) {
      log.warn("Assistant read tool '{}' failed", call.name(), e);
      result = ToolResult.error(messages.get("assistant.error.tool_failed"));
    }
    return toolResult(call.id(), result);
  }

  private PreviewOutcome previewSafely(
      ActionTool action, LlmContent.ToolUse call, ToolContext ctx) {
    JsonNode input = call.input() == null ? mapper.createObjectNode() : call.input();
    try {
      return action.preview(input, ctx);
    } catch (RuntimeException e) {
      log.warn("Assistant action preview '{}' failed", call.name(), e);
      return new PreviewOutcome.Rejected(
          ToolResult.error(messages.get("assistant.error.tool_failed")));
    }
  }

  private LlmContent.ToolResult toolResult(String toolUseId, ToolResult result) {
    return new LlmContent.ToolResult(
        toolUseId, serialize(result), result.status() == ToolResult.Status.ERROR);
  }

  private String serialize(ToolResult result) {
    try {
      return mapper.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      log.warn("Failed to serialize tool result", e);
      return "{\"status\":\"ERROR\"}";
    }
  }

  /** Per-turn text streamer: opens the content block lazily on the first delta. */
  private static final class TurnStreamer implements LlmStreamListener {

    private final ChatStream sink;
    private final int index;
    private boolean opened;

    TurnStreamer(ChatStream sink, int index) {
      this.sink = sink;
      this.index = index;
    }

    @Override
    public void onTextDelta(String text) {
      if (sink.isCancelled() || text == null || text.isEmpty()) {
        return;
      }
      if (!opened) {
        sink.contentBlockStart(index);
        opened = true;
      }
      sink.contentBlockDelta(index, text);
    }

    /** Closes the block if one was opened; returns whether it was. */
    boolean finish() {
      if (opened) {
        sink.contentBlockStop(index);
      }
      return opened;
    }
  }
}
