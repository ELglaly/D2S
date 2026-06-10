package com.schoolbridge.api.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.cache.AssistantCache;
import com.schoolbridge.api.assistant.dto.ActionPreview;
import com.schoolbridge.api.assistant.dto.AskRequest;
import com.schoolbridge.api.assistant.dto.AssistantAnswer;
import com.schoolbridge.api.assistant.llm.AssistantProperties;
import com.schoolbridge.api.assistant.llm.LlmContent;
import com.schoolbridge.api.assistant.llm.LlmGateway;
import com.schoolbridge.api.assistant.llm.LlmResponse;
import com.schoolbridge.api.assistant.llm.LlmUsage;
import com.schoolbridge.api.assistant.llm.SystemPrompt;
import com.schoolbridge.api.assistant.tools.ActionTool;
import com.schoolbridge.api.assistant.tools.PreviewOutcome;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolRegistry;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.common.i18n.MessageResolver;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssistantActionOrchestrationTest {

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
  void actionProposalHaltsWithConfirmRequired() {
    StubActionTool action = new StubActionTool();
    ToolRegistry registry = new ToolRegistry(List.of(action), true);
    LlmGateway gateway =
        request ->
            new LlmResponse(
                List.of(new LlmContent.ToolUse("t1", "mark_all_present", JSON.createObjectNode())),
                "tool_use",
                new LlmUsage(5, 5));
    when(cache.key(ctx.userId(), "mark all present in 5a")).thenReturn("k");
    when(cache.get("k")).thenReturn(Optional.empty());

    AssistantServiceImpl service =
        new AssistantServiceImpl(
            gateway,
            registry,
            new SystemPrompt(),
            new AssistantProperties(),
            cache,
            messages,
            JSON);

    AssistantAnswer answer = service.ask(new AskRequest("mark all present in 5a", null), ctx);

    assertThat(answer.outcome()).isEqualTo(AssistantAnswer.Outcome.CONFIRM_REQUIRED);
    assertThat(answer.pendingAction()).isNotNull();
    assertThat(answer.pendingAction().token()).isEqualTo("tok-123");
    assertThat(answer.text()).isEqualTo("en summary"); // English ctx → summaryEn
  }

  private static final class StubActionTool implements ActionTool {
    @Override
    public String name() {
      return "mark_all_present";
    }

    @Override
    public String description() {
      return "mark all present";
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
    public PreviewOutcome preview(JsonNode args, ToolContext ctx) {
      return new PreviewOutcome.Prepared(
          new ActionPreview(
              "tok-123",
              "ar summary",
              "en summary",
              Map.of("studentCount", 24),
              false,
              Instant.now().plusSeconds(300)));
    }

    @Override
    public ToolResult execute(String token, ToolContext ctx) {
      return ToolResult.ok("done");
    }
  }
}
