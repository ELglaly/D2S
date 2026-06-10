package com.schoolbridge.api.assistant;

import com.schoolbridge.api.assistant.audit.AssistantAuditRecorder;
import com.schoolbridge.api.assistant.confirm.ConfirmIntent;
import com.schoolbridge.api.assistant.confirm.PendingAction;
import com.schoolbridge.api.assistant.confirm.PendingActionStore;
import com.schoolbridge.api.assistant.dto.ConfirmActionRequest;
import com.schoolbridge.api.assistant.dto.ConfirmResult;
import com.schoolbridge.api.assistant.llm.AssistantProperties;
import com.schoolbridge.api.assistant.tools.ActionTool;
import com.schoolbridge.api.assistant.tools.Tool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolRegistry;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.common.i18n.MessageResolver;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Loads a previewed action by token, enforces the security gates (owner, expiry, typed confirmation
 * for destructive actions), then runs {@code ActionTool.execute} — which atomically consumes the
 * token and mutates through the existing service. The LLM is never on this path.
 */
@Service
public class AssistantActionServiceImpl implements AssistantActionService {

  private final PendingActionStore store;
  private final ToolRegistry registry;
  private final AssistantProperties properties;
  private final MessageResolver messages;
  private final AssistantAuditRecorder recorder;

  public AssistantActionServiceImpl(
      PendingActionStore store,
      ToolRegistry registry,
      AssistantProperties properties,
      MessageResolver messages,
      AssistantAuditRecorder recorder) {
    this.store = store;
    this.registry = registry;
    this.properties = properties;
    this.messages = messages;
    this.recorder = recorder;
  }

  @Override
  public ConfirmResult confirm(String token, ConfirmActionRequest body, ToolContext ctx) {
    Optional<PendingAction> peeked = store.peek(token);
    if (peeked.isEmpty() || !peeked.get().userId().equals(ctx.userId())) {
      return ConfirmResult.invalid(messages.get("assistant.action.invalid"));
    }
    PendingAction pending = peeked.get();
    if (pending.expiresAt().isBefore(Instant.now())) {
      store.consume(token);
      return ConfirmResult.invalid(messages.get("assistant.action.expired"));
    }
    if (pending.destructive() && properties.getActions().isDestructiveRequireTypedConfirm()) {
      String confirmation = body == null ? null : body.confirmation();
      if (!ConfirmIntent.isAffirmative(confirmation)) {
        return ConfirmResult.typedConfirmNeeded(
            messages.get("assistant.action.typed_confirm_required"));
      }
    }
    Optional<Tool> tool = registry.find(pending.toolName(), ctx);
    if (tool.isEmpty() || !(tool.get() instanceof ActionTool action)) {
      store.consume(token);
      return ConfirmResult.invalid(messages.get("assistant.action.disabled"));
    }
    ToolResult result = action.execute(token, ctx);
    recorder.execute(ctx, pending.toolName(), result);
    return switch (result.status()) {
      case OK -> ConfirmResult.executed(messages.get("assistant.action.executed"), result.data());
      case DENIED ->
          ConfirmResult.denied(
              result.message() != null ? result.message() : messages.get("error.forbidden"));
      default ->
          ConfirmResult.invalid(
              result.message() != null
                  ? result.message()
                  : messages.get("assistant.action.invalid"));
    };
  }

  @Override
  public ConfirmResult cancel(String token, ToolContext ctx) {
    Optional<PendingAction> peeked = store.peek(token);
    if (peeked.isEmpty() || !peeked.get().userId().equals(ctx.userId())) {
      return ConfirmResult.invalid(messages.get("assistant.action.invalid"));
    }
    store.consume(token);
    recorder.cancel(ctx, peeked.get().toolName());
    return ConfirmResult.cancelled(messages.get("assistant.action.cancelled"));
  }
}
