package com.schoolbridge.api.assistant.tools.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.confirm.ConfirmationTokenService;
import com.schoolbridge.api.assistant.confirm.PendingAction;
import com.schoolbridge.api.assistant.confirm.PendingActionStore;
import com.schoolbridge.api.assistant.dto.ActionPreview;
import com.schoolbridge.api.assistant.llm.AssistantProperties;
import com.schoolbridge.api.assistant.tools.PreviewOutcome;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.common.i18n.MessageResolver;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AbstractActionToolTest {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @Mock PendingActionStore store;
  @Mock ConfirmationTokenService tokens;
  @Mock MessageResolver messages;
  @Mock ToolSupport toolSupport;

  private final AssistantProperties properties = new AssistantProperties();
  private ActionSupport actions;
  private TestActionTool tool;

  private final ToolContext ctx =
      new ToolContext(
          UUID.randomUUID(),
          new StaffPrincipal(UUID.randomUUID(), UUID.randomUUID(), UserRole.SCHOOL_ADMIN),
          UserRole.SCHOOL_ADMIN,
          java.util.Locale.ENGLISH,
          null);

  @BeforeEach
  void setUp() {
    actions = new ActionSupport(store, tokens, properties, messages, toolSupport, JSON);
    tool = new TestActionTool(actions);
    lenient().when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  void previewStoresPendingAndReturnsPrepared() {
    when(tokens.generate()).thenReturn("tok");
    JsonNode resolvedArgs = JSON.createObjectNode().put("classId", "c1");
    tool.prepResult = tool.ready(resolvedArgs, "ar-summary", "en-summary", Map.of("k", "v"), 1);

    PreviewOutcome outcome = tool.preview(JSON.createObjectNode(), ctx);

    assertThat(outcome).isInstanceOf(PreviewOutcome.Prepared.class);
    ActionPreview preview = ((PreviewOutcome.Prepared) outcome).preview();
    assertThat(preview.token()).isEqualTo("tok");
    assertThat(preview.summaryEn()).isEqualTo("en-summary");

    org.mockito.ArgumentCaptor<PendingAction> captor =
        org.mockito.ArgumentCaptor.forClass(PendingAction.class);
    verify(store).put(captor.capture(), any());
    PendingAction stored = captor.getValue();
    assertThat(stored.userId()).isEqualTo(ctx.userId());
    assertThat(stored.toolName()).isEqualTo("test_action");
    assertThat(stored.resolvedArgs()).isEqualTo(resolvedArgs);
  }

  @Test
  void previewRejectsWhenImpactExceedsBulkCap() {
    tool.prepResult =
        tool.ready(
            JSON.createObjectNode(),
            "ar",
            "en",
            Map.of(),
            properties.getActions().getMaxBulkImpact() + 1);

    PreviewOutcome outcome = tool.preview(JSON.createObjectNode(), ctx);

    assertThat(outcome).isInstanceOf(PreviewOutcome.Rejected.class);
    verify(store, never()).put(any(), any());
  }

  @Test
  void previewPropagatesRejection() {
    tool.prepResult = tool.reject(ToolResult.denied("nope"));
    PreviewOutcome outcome = tool.preview(JSON.createObjectNode(), ctx);
    assertThat(outcome).isInstanceOf(PreviewOutcome.Rejected.class);
    verify(store, never()).put(any(), any());
  }

  @Test
  void executeConsumesTokenAndRunsDoExecute() {
    JsonNode resolvedArgs = JSON.createObjectNode().put("classId", "c1");
    when(store.consume("tok")).thenReturn(Optional.of(pending("tok", ctx.userId(), "test_action")));

    ToolResult result = tool.execute("tok", ctx);

    assertThat(result.isOk()).isTrue();
    assertThat(tool.executed).isTrue();
  }

  @Test
  void executeReturnsErrorOnReplayedOrExpiredToken() {
    when(store.consume("gone")).thenReturn(Optional.empty());
    assertThat(tool.execute("gone", ctx).status()).isEqualTo(ToolResult.Status.ERROR);
    assertThat(tool.executed).isFalse();
  }

  @Test
  void executeRejectsTokenBelongingToAnotherUser() {
    when(store.consume("tok"))
        .thenReturn(Optional.of(pending("tok", UUID.randomUUID(), "test_action")));
    assertThat(tool.execute("tok", ctx).status()).isEqualTo(ToolResult.Status.ERROR);
    assertThat(tool.executed).isFalse();
  }

  @Test
  void executeRejectsExpiredToken() {
    PendingAction expired =
        new PendingAction(
            "tok",
            ctx.userId(),
            ctx.schoolId(),
            "test_action",
            JSON.createObjectNode(),
            Map.of(),
            false,
            Instant.now().minusSeconds(600),
            Instant.now().minusSeconds(1));
    when(store.consume("tok")).thenReturn(Optional.of(expired));
    assertThat(tool.execute("tok", ctx).status()).isEqualTo(ToolResult.Status.ERROR);
    assertThat(tool.executed).isFalse();
  }

  private PendingAction pending(String token, UUID userId, String toolName) {
    return new PendingAction(
        token,
        userId,
        ctx.schoolId(),
        toolName,
        JSON.createObjectNode(),
        Map.of(),
        false,
        Instant.now(),
        Instant.now().plusSeconds(300));
  }

  /** Minimal concrete action tool whose prepare/doExecute are test-controllable. */
  private static final class TestActionTool extends AbstractActionTool {
    PrepResult prepResult;
    boolean executed;

    TestActionTool(ActionSupport actions) {
      super(actions);
    }

    @Override
    public String name() {
      return "test_action";
    }

    @Override
    public String description() {
      return "test";
    }

    @Override
    public JsonNode inputSchema() {
      return com.schoolbridge.api.assistant.tools.support.Schema.empty();
    }

    @Override
    public Set<UserRole> roles() {
      return Set.of(UserRole.SCHOOL_ADMIN);
    }

    @Override
    protected PrepResult prepare(JsonNode args, ToolContext ctx) {
      return prepResult;
    }

    @Override
    protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
      executed = true;
      return ToolResult.ok("done");
    }
  }
}
