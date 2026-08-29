package com.schoolbridge.api.assistant.audit;

import com.schoolbridge.api.assistant.dto.AssistantAnswer;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.common.audit.AuditService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Writes one audit row per assistant interaction: {@code assistant.ask} / {@code .action.preview} /
 * {@code .action.execute} / {@code .action.cancel}. Metadata is always a {@code HashMap} (never
 * {@code Map.of}) and never carries PII, the question text, or secrets.
 */
@Component
public class AssistantAuditRecorder {

  private static final String ENTITY = "Assistant";

  private final AuditService audit;

  public AssistantAuditRecorder(AuditService audit) {
    this.audit = audit;
  }

  public void ask(ToolContext ctx, AssistantAnswer answer) {
    Map<String, Object> metadata = baseMetadata(answer);
    metadata.put("outcome", answer.outcome().name());
    audit.record(ctx.schoolId(), ctx.userId(), "assistant.ask", ENTITY, null, metadata);
  }

  public void preview(ToolContext ctx, AssistantAnswer answer) {
    Map<String, Object> metadata = baseMetadata(answer);
    if (answer.pendingAction() != null) {
      metadata.put("token", answer.pendingAction().token());
      metadata.put("destructive", answer.pendingAction().destructive());
      Object action =
          answer.pendingAction().impact() == null
              ? null
              : answer.pendingAction().impact().get("action");
      if (action != null) {
        metadata.put("toolName", action);
      }
    }
    audit.record(ctx.schoolId(), ctx.userId(), "assistant.action.preview", ENTITY, null, metadata);
  }

  public void execute(ToolContext ctx, String toolName, ToolResult result) {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("toolName", toolName);
    metadata.put("status", result.status().name());
    audit.record(ctx.schoolId(), ctx.userId(), "assistant.action.execute", ENTITY, null, metadata);
  }

  public void cancel(ToolContext ctx, String toolName) {
    Map<String, Object> metadata = new HashMap<>();
    if (toolName != null) {
      metadata.put("toolName", toolName);
    }
    audit.record(ctx.schoolId(), ctx.userId(), "assistant.action.cancel", ENTITY, null, metadata);
  }

  private static Map<String, Object> baseMetadata(AssistantAnswer answer) {
    return new HashMap<>(answer.metadata() == null ? Map.of() : answer.metadata());
  }
}

