package com.schoolbridge.api.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.audit.AssistantAuditRecorder;
import com.schoolbridge.api.assistant.confirm.PendingAction;
import com.schoolbridge.api.assistant.confirm.PendingActionStore;
import com.schoolbridge.api.assistant.dto.ConfirmActionRequest;
import com.schoolbridge.api.assistant.dto.ConfirmResult;
import com.schoolbridge.api.assistant.llm.AssistantProperties;
import com.schoolbridge.api.assistant.tools.ActionTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolRegistry;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.common.i18n.MessageResolver;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssistantActionServiceImplTest {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @Mock PendingActionStore store;
  @Mock ToolRegistry registry;
  @Mock MessageResolver messages;
  @Mock AssistantAuditRecorder recorder;
  @Mock ActionTool actionTool;

  private AssistantActionServiceImpl service;

  private final ToolContext ctx =
      new ToolContext(
          UUID.randomUUID(),
          new StaffPrincipal(UUID.randomUUID(), UUID.randomUUID(), UserRole.SCHOOL_ADMIN),
          UserRole.SCHOOL_ADMIN,
          Locale.ENGLISH,
          null);

  @BeforeEach
  void setUp() {
    service =
        new AssistantActionServiceImpl(
            store, registry, new AssistantProperties(), messages, recorder);
    lenient().when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  void confirmExecutesNonDestructiveAction() {
    when(store.peek("tok")).thenReturn(Optional.of(pending("add_student", ctx.userId(), false)));
    when(registry.find("add_student", ctx)).thenReturn(Optional.of(actionTool));
    when(actionTool.execute("tok", ctx)).thenReturn(ToolResult.ok(Map.of("id", "x")));

    ConfirmResult result = service.confirm("tok", null, ctx);

    assertThat(result.status()).isEqualTo("EXECUTED");
    verify(recorder).execute(ctx, "add_student", ToolResult.ok(Map.of("id", "x")));
  }

  @Test
  void confirmRejectsUnknownOrExpiredToken() {
    when(store.peek("gone")).thenReturn(Optional.empty());
    assertThat(service.confirm("gone", null, ctx).status()).isEqualTo("INVALID");
  }

  @Test
  void confirmRejectsTokenOfAnotherUser() {
    when(store.peek("tok"))
        .thenReturn(Optional.of(pending("add_student", UUID.randomUUID(), false)));
    assertThat(service.confirm("tok", null, ctx).status()).isEqualTo("INVALID");
    verify(actionTool, never()).execute(any(), any());
  }

  @Test
  void confirmRequiresTypedConfirmationForDestructiveAction() {
    when(store.peek("tok")).thenReturn(Optional.of(pending("delete_student", ctx.userId(), true)));

    ConfirmResult result = service.confirm("tok", new ConfirmActionRequest("maybe"), ctx);

    assertThat(result.status()).isEqualTo("TYPED_CONFIRM_NEEDED");
    verify(actionTool, never()).execute(any(), any());
  }

  @Test
  void confirmExecutesDestructiveWhenTypedYes() {
    when(store.peek("tok")).thenReturn(Optional.of(pending("delete_student", ctx.userId(), true)));
    when(registry.find("delete_student", ctx)).thenReturn(Optional.of(actionTool));
    when(actionTool.execute("tok", ctx)).thenReturn(ToolResult.ok(Map.of("deleted", true)));

    ConfirmResult result = service.confirm("tok", new ConfirmActionRequest("yes"), ctx);

    assertThat(result.status()).isEqualTo("EXECUTED");
  }

  @Test
  void cancelConsumesTokenAndAudits() {
    when(store.peek("tok")).thenReturn(Optional.of(pending("add_student", ctx.userId(), false)));

    ConfirmResult result = service.cancel("tok", ctx);

    assertThat(result.status()).isEqualTo("CANCELLED");
    verify(store).consume("tok");
    verify(recorder).cancel(ctx, "add_student");
  }

  private PendingAction pending(String toolName, UUID userId, boolean destructive) {
    return new PendingAction(
        "tok",
        userId,
        ctx.schoolId(),
        toolName,
        JSON.createObjectNode(),
        Map.of(),
        destructive,
        Instant.now(),
        Instant.now().plusSeconds(300));
  }
}
