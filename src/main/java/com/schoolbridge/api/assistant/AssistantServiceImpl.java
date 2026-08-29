package com.schoolbridge.api.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.cache.AssistantCache;
import com.schoolbridge.api.assistant.dto.ActionPreview;
import com.schoolbridge.api.assistant.dto.AskRequest;
import com.schoolbridge.api.assistant.dto.AssistantAnswer;
import com.schoolbridge.api.assistant.llm.AssistantProperties;
import com.schoolbridge.api.assistant.llm.LlmContent;
import com.schoolbridge.api.assistant.llm.LlmGateway;
import com.schoolbridge.api.assistant.llm.LlmMessage;
import com.schoolbridge.api.assistant.llm.LlmRequest;
import com.schoolbridge.api.assistant.llm.LlmResponse;
import com.schoolbridge.api.assistant.llm.LlmToolSpec;
import com.schoolbridge.api.assistant.llm.LlmUsage;
import com.schoolbridge.api.assistant.llm.SystemPrompt;
import com.schoolbridge.api.assistant.rag.ContextAugmenter;
import com.schoolbridge.api.assistant.rag.RagRetriever;
import com.schoolbridge.api.assistant.rag.RetrievedChunk;
import com.schoolbridge.api.assistant.tools.ActionTool;
import com.schoolbridge.api.assistant.tools.PreviewOutcome;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.Tool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolRegistry;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.ToolResultProjector;
import com.schoolbridge.api.assistant.tools.ToolSelector;
import com.schoolbridge.api.common.i18n.MessageResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Read-orchestration loop. Each iteration asks the model; if it returns text we answer (and cache),
 * if it calls a read tool we execute it and feed the result back, up to {@code
 * max-tool-iterations}. The model never reaches the database â€” only the role-scoped tool catalog
 * and structured results. Action (mutating) tools are handled by the confirm gate added in Phase 3.
 */
@Service
public class AssistantServiceImpl implements AssistantService {

  private static final Logger log = LoggerFactory.getLogger(AssistantServiceImpl.class);

  private final LlmGateway gateway;
  private final ToolRegistry registry;
  private final SystemPrompt systemPrompt;
  private final AssistantProperties properties;
  private final AssistantCache cache;
  private final MessageResolver messages;
  private final ObjectMapper mapper;
  private final RagRetriever retriever;
  private final ContextAugmenter augmenter;
  private final ToolResultProjector projector;
  private final ToolSelector selector;

  public AssistantServiceImpl(
      LlmGateway gateway,
      ToolRegistry registry,
      SystemPrompt systemPrompt,
      AssistantProperties properties,
      AssistantCache cache,
      MessageResolver messages,
      ObjectMapper mapper,
      RagRetriever retriever,
      ContextAugmenter augmenter,
      ToolResultProjector projector,
      ToolSelector selector) {
    this.gateway = gateway;
    this.registry = registry;
    this.systemPrompt = systemPrompt;
    this.properties = properties;
    this.cache = cache;
    this.messages = messages;
    this.mapper = mapper;
    this.retriever = retriever;
    this.augmenter = augmenter;
    this.projector = projector;
    this.selector = selector;
  }

  @Override
  public AssistantAnswer ask(AskRequest request, ToolContext ctx) {
    String question = request.question() == null ? "" : request.question().trim();
    if (question.isEmpty()) {
      return AssistantAnswer.error(messages.get("assistant.error.empty"), baseMetadata(ctx));
    }
    if (question.length() > properties.getMaxQuestionLength()) {
      return AssistantAnswer.error(messages.get("assistant.error.too_long"), baseMetadata(ctx));
    }

    String cacheKey = cache.key(ctx.userId(), question);
    Optional<String> cached = cache.get(cacheKey);
    if (cached.isPresent()) {
      Map<String, Object> meta = baseMetadata(ctx);
      meta.put("cached", true);
      return AssistantAnswer.answered(cached.get(), meta);
    }

    List<Tool> visible = registry.toolsFor(ctx);
    List<Tool> available =
        properties.isToolGatingEnabled() ? selector.select(question, visible) : visible;
    List<LlmToolSpec> tools =
        available.stream()
            .map(t -> new LlmToolSpec(t.name(), t.description(), t.inputSchema()))
            .toList();
    // System stays byte-stable (no per-query RAG): the provider can cache this prefix + the tool
    // catalog across turns and across this turn's tool-loop iterations. Retrieved context rides on
    // the user turn instead.
    String system = systemPrompt.build(ctx);

    int retrievedChunks = 0;
    String contextBlock = "";
    if (properties.getRag().isEnabled()) {
      List<RetrievedChunk> chunks = retriever.retrieve(question, ctx);
      retrievedChunks = chunks.size();
      contextBlock = augmenter.buildContextBlock(chunks);
    }

    List<LlmMessage> history = new ArrayList<>();
    history.add(userTurn(contextBlock, question));

    LlmUsage usage = LlmUsage.zero();
    List<String> toolsInvoked = new ArrayList<>();

    int iterations = 0;
    while (iterations < properties.getMaxToolIterations()) {
      iterations++;
      LlmResponse response =
          gateway.converse(
              new LlmRequest(
                  system, history, tools, properties.getModel(), properties.getMaxTokens()));
      usage = usage.plus(response.usage());

      if (!response.wantsTools()) {
        String answer = response.text();
        cache.put(cacheKey, answer, properties.getReadCacheTtl());
        Map<String, Object> meta = metadata(ctx, iterations, usage, toolsInvoked, false);
        meta.put("retrievedChunks", retrievedChunks);
        return AssistantAnswer.answered(answer, meta);
      }

      history.add(LlmMessage.assistant(response.content()));
      List<LlmContent> toolResults = new ArrayList<>();
      for (LlmContent.ToolUse call : response.toolUses()) {
        toolsInvoked.add(call.name());
        Optional<Tool> found = registry.find(call.name(), ctx);
        if (found.isPresent() && found.get() instanceof ActionTool action) {
          PreviewOutcome outcome = previewSafely(action, call, ctx);
          if (outcome instanceof PreviewOutcome.Prepared prepared) {
            // Halt: a mutation needs explicit confirmation (Phase B happens on /confirm).
            ActionPreview preview = prepared.preview();
            return AssistantAnswer.confirmRequired(
                preview,
                "ar".equals(ctx.language().getLanguage())
                    ? preview.summaryAr()
                    : preview.summaryEn(),
                metadata(ctx, iterations, usage, toolsInvoked, false));
          }
          toolResults.add(toolResult(call.id(), ((PreviewOutcome.Rejected) outcome).result()));
        } else {
          toolResults.add(invokeRead(call, found, ctx));
        }
      }
      history.add(LlmMessage.user(toolResults));
    }

    return AssistantAnswer.error(
        messages.get("assistant.error.max_iterations"),
        metadata(ctx, iterations, usage, toolsInvoked, false));
  }

  /**
   * The opening user turn: the question, optionally preceded by a retrieved-context text block
   * (kept off the system prompt so the cached prefix stays stable).
   */
  private static LlmMessage userTurn(String contextBlock, String question) {
    if (contextBlock == null || contextBlock.isBlank()) {
      return LlmMessage.user(question);
    }
    return LlmMessage.user(
        List.of(new LlmContent.Text(contextBlock.strip()), new LlmContent.Text(question)));
  }

  /** Resolves and runs a read tool, returning the tool_result content block for the model. */
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

  /**
   * Previews an action tool, converting any thrown exception into a fed-back error (never halts).
   */
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
    return projector.serialize(result);
  }

  private Map<String, Object> baseMetadata(ToolContext ctx) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("language", ctx.language().getLanguage());
    meta.put("role", ctx.role().name());
    meta.put("cached", false);
    return meta;
  }

  private Map<String, Object> metadata(
      ToolContext ctx, int iterations, LlmUsage usage, List<String> toolsInvoked, boolean cached) {
    Map<String, Object> meta = baseMetadata(ctx);
    meta.put("iterations", iterations);
    meta.put("inputTokens", usage.inputTokens());
    meta.put("outputTokens", usage.outputTokens());
    meta.put("toolsInvoked", List.copyOf(toolsInvoked));
    meta.put("cached", cached);
    return meta;
  }
}

