package com.schoolbridge.api.assistant.tools.announcements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.announcements.dto.AnnouncementResponse;
import com.schoolbridge.api.announcements.service.AnnouncementService;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.AbstractActionTool;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.identity.UserRole;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ADMIN — recall an announcement (destructive). Mirrors {@code POST /announcements/{id}/recall}.
 */
@Component
public class RecallAnnouncementTool extends AbstractActionTool {

  private final AnnouncementService announcements;

  public RecallAnnouncementTool(ActionSupport actions, AnnouncementService announcements) {
    super(actions);
    this.announcements = announcements;
  }

  @Override
  public String name() {
    return "recall_announcement";
  }

  @Override
  public String description() {
    return "Recall (withdraw) an announcement.";
  }

  @Override
  public boolean destructive() {
    return true;
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("announcementRef", "A distinctive phrase from the announcement body", true)
        .build();
  }

  @Override
  public Set<UserRole> roles() {
    return Set.of(UserRole.SCHOOL_ADMIN);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<AnnouncementResponse> ann =
        actions.tools().announcement(Args.str(args, "announcementRef"));
    if (ann.clarified()) {
      return clarify(ann);
    }
    ObjectNode resolved = newArgs();
    resolved.put("announcementId", ann.value().id().toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("announcement", snippet(ann.value().body()));
    impact.put("destructive", true);

    return readyMsg(resolved, "assistant.action.recall_announcement.summary", impact, 1);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    return ToolResult.ok(announcements.recall(uuid(resolvedArgs, "announcementId")));
  }

  private static String snippet(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= 40 ? body : body.substring(0, 40) + "…";
  }
}
