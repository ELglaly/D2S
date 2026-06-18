package com.schoolbridge.api.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.cache.AssistantCache;
import com.schoolbridge.api.assistant.dto.AskRequest;
import com.schoolbridge.api.assistant.dto.AssistantAnswer;
import com.schoolbridge.api.assistant.llm.AssistantProperties;
import com.schoolbridge.api.assistant.llm.LlmContent;
import com.schoolbridge.api.assistant.llm.LlmGateway;
import com.schoolbridge.api.assistant.llm.LlmRequest;
import com.schoolbridge.api.assistant.llm.LlmResponse;
import com.schoolbridge.api.assistant.llm.LlmUsage;
import com.schoolbridge.api.assistant.llm.SystemPrompt;
import com.schoolbridge.api.assistant.rag.ContextAugmenter;
import com.schoolbridge.api.assistant.rag.RagRetriever;
import com.schoolbridge.api.assistant.rag.RetrievedChunk;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.Tool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolRegistry;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.ToolResultProjector;
import com.schoolbridge.api.assistant.tools.ToolSelector;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.common.i18n.MessageResolver;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssistantServiceImplTest {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @Mock AssistantCache cache;
  @Mock MessageResolver messages;

  private final ToolContext ctx =
      new ToolContext(
          UUID.randomUUID(),
          new StaffPrincipal(UUID.randomUUID(), UUID.randomUUID(), UserRole.TEACHER),
          UserRole.TEACHER,
          Locale.ENGLISH,
          null);

  @Test
  void directTextAnswerIsReturnedAndCached() {
    ScriptedGateway gateway = new ScriptedGateway(text("You teach 3 classes."));
    when(cache.key(ctx.userId(), "how many classes do i teach")).thenReturn("k");
    when(cache.get("k")).thenReturn(Optional.empty());

    AssistantAnswer answer =
        service(gateway, registry()).ask(ask("how many classes do i teach"), ctx);

    assertThat(answer.outcome()).isEqualTo(AssistantAnswer.Outcome.ANSWERED);
    assertThat(answer.text()).isEqualTo("You teach 3 classes.");
    assertThat(gateway.calls()).isEqualTo(1);
  }

  @Test
  void readToolIsExecutedAndResultFedBack() {
    CountingTool tool = new CountingTool();
    ScriptedGateway gateway =
        new ScriptedGateway(toolCall("t1", "counting_tool"), text("Here is what I found."));
    when(cache.key(ctx.userId(), "list it")).thenReturn("k");
    when(cache.get("k")).thenReturn(Optional.empty());

    AssistantAnswer answer = service(gateway, registry(tool)).ask(ask("list it"), ctx);

    assertThat(answer.outcome()).isEqualTo(AssistantAnswer.Outcome.ANSWERED);
    assertThat(tool.invocations.get()).isEqualTo(1);
    assertThat(gateway.calls()).isEqualTo(2);
    // The second request must carry the tool result back to the model.
    LlmRequest second = gateway.requests.get(1);
    LlmContent last = second.messages().get(second.messages().size() - 1).content().get(0);
    assertThat(last).isInstanceOf(LlmContent.ToolResult.class);
    assertThat(answer.metadata().get("toolsInvoked")).isEqualTo(List.of("counting_tool"));
  }

  @Test
  void cacheHitShortCircuitsTheModel() {
    ScriptedGateway gateway = new ScriptedGateway(text("should not be used"));
    when(cache.key(ctx.userId(), "cached question")).thenReturn("k");
    when(cache.get("k")).thenReturn(Optional.of("cached answer"));

    AssistantAnswer answer = service(gateway, registry()).ask(ask("cached question"), ctx);

    assertThat(answer.outcome()).isEqualTo(AssistantAnswer.Outcome.ANSWERED);
    assertThat(answer.text()).isEqualTo("cached answer");
    assertThat(answer.metadata().get("cached")).isEqualTo(true);
    assertThat(gateway.calls()).isEqualTo(0);
  }

  @Test
  void maxIterationsReachedReturnsError() {
    ScriptedGateway gateway = new ScriptedGateway(true, toolCall("t", "counting_tool"));
    when(cache.key(ctx.userId(), "loop")).thenReturn("k");
    when(cache.get("k")).thenReturn(Optional.empty());
    AssistantProperties props = new AssistantProperties();
    props.setMaxToolIterations(2);

    AssistantAnswer answer =
        service(gateway, registry(new CountingTool()), props).ask(ask("loop"), ctx);

    assertThat(answer.outcome()).isEqualTo(AssistantAnswer.Outcome.ERROR);
    assertThat(gateway.calls()).isEqualTo(2);
  }

  @Test
  void blankQuestionIsRejectedWithoutCallingModel() {
    ScriptedGateway gateway = new ScriptedGateway(text("x"));
    AssistantAnswer answer = service(gateway, registry()).ask(new AskRequest("   ", null), ctx);
    assertThat(answer.outcome()).isEqualTo(AssistantAnswer.Outcome.ERROR);
    assertThat(gateway.calls()).isEqualTo(0);
    verifyNoInteractions(cache);
  }

  @Test
  void ragContextIsAttachedToUserTurnWhenEnabled() {
    AssistantProperties props = new AssistantProperties();
    props.getRag().setEnabled(true);
    ScriptedGateway gateway = new ScriptedGateway(text("Absence answer."));
    when(cache.key(ctx.userId(), "what is the absence policy")).thenReturn("k");
    when(cache.get("k")).thenReturn(Optional.empty());
    RagRetriever retriever =
        new RagRetriever(null, props, null) {
          @Override
          public List<RetrievedChunk> retrieve(String query, ToolContext context) {
            return List.of(
                new RetrievedChunk("Absences need a written note.", "FAQ", "Attendance"));
          }
        };

    AssistantServiceImpl service =
        new AssistantServiceImpl(
            gateway,
            registry(),
            new SystemPrompt(),
            props,
            cache,
            messages,
            JSON,
            retriever,
            new ContextAugmenter(props),
            new ToolResultProjector(JSON, props),
            new ToolSelector(new SimpleMeterRegistry()));

    AssistantAnswer answer = service.ask(ask("what is the absence policy"), ctx);

    assertThat(answer.outcome()).isEqualTo(AssistantAnswer.Outcome.ANSWERED);
    assertThat(answer.metadata().get("retrievedChunks")).isEqualTo(1);
    LlmRequest request = gateway.requests.get(0);
    // System prompt stays free of retrieved context so the cached prefix is stable across turns.
    assertThat(request.system()).doesNotContain("Absences need a written note.");
    String firstUserTurn =
        request.messages().get(0).content().stream()
            .filter(c -> c instanceof LlmContent.Text)
            .map(c -> ((LlmContent.Text) c).text())
            .collect(java.util.stream.Collectors.joining("\n"));
    assertThat(firstUserTurn).contains("Reference context");
    assertThat(firstUserTurn).contains("Absences need a written note.");
    assertThat(firstUserTurn).contains("what is the absence policy");
  }

  // --- helpers --------------------------------------------------------------

  private AssistantServiceImpl service(LlmGateway gateway, ToolRegistry registry) {
    return service(gateway, registry, new AssistantProperties());
  }

  private AssistantServiceImpl service(
      LlmGateway gateway, ToolRegistry registry, AssistantProperties props) {
    return new AssistantServiceImpl(
        gateway,
        registry,
        new SystemPrompt(),
        props,
        cache,
        messages,
        JSON,
        new RagRetriever(null, props, null),
        new ContextAugmenter(props),
        new ToolResultProjector(JSON, props),
        new ToolSelector(new SimpleMeterRegistry()));
  }

  private ToolRegistry registry(Tool... tools) {
    return new ToolRegistry(List.of(tools), true);
  }

  private static AskRequest ask(String q) {
    return new AskRequest(q, null);
  }

  private static LlmResponse text(String t) {
    return new LlmResponse(List.of(new LlmContent.Text(t)), "end_turn", new LlmUsage(10, 5));
  }

  private static LlmResponse toolCall(String id, String name) {
    return new LlmResponse(
        List.of(new LlmContent.ToolUse(id, name, JSON.createObjectNode())),
        "tool_use",
        new LlmUsage(10, 5));
  }

  /** Returns scripted responses in order; with {@code repeatLast}, keeps returning the last one. */
  private static final class ScriptedGateway implements LlmGateway {
    private final List<LlmResponse> scripted;
    private final boolean repeatLast;
    final List<LlmRequest> requests = new ArrayList<>();
    private int index;

    ScriptedGateway(LlmResponse... responses) {
      this(false, responses);
    }

    ScriptedGateway(boolean repeatLast, LlmResponse... responses) {
      this.scripted = List.of(responses);
      this.repeatLast = repeatLast;
    }

    @Override
    public LlmResponse converse(LlmRequest request) {
      requests.add(request);
      LlmResponse r = scripted.get(Math.min(index, scripted.size() - 1));
      if (!repeatLast) {
        index++;
      }
      return r;
    }

    int calls() {
      return requests.size();
    }
  }

  private static final class CountingTool implements ReadTool {
    final AtomicInteger invocations = new AtomicInteger();

    @Override
    public String name() {
      return "counting_tool";
    }

    @Override
    public String description() {
      return "counts invocations";
    }

    @Override
    public JsonNode inputSchema() {
      return Schema.empty();
    }

    @Override
    public Set<UserRole> roles() {
      return Set.of(UserRole.TEACHER, UserRole.SCHOOL_ADMIN);
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
      invocations.incrementAndGet();
      return ToolResult.ok(List.of("a", "b"));
    }
  }
}
