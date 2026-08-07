package com.schoolbridge.api.assistant.tools.announcements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.announcements.dto.CreateAnnouncementRequest;
import com.schoolbridge.api.announcements.enums.AnnouncementScope;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.announcements.service.AnnouncementService;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.AbstractActionTool;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.common.security.PermissionsHelper;
import com.schoolbridge.api.common.security.authz.Permission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * TEACHER/ADMIN — post a CLASS-scoped announcement (fans out). Mirrors {@code POST /announcements}
 * (CLASS).
 */
@Component
public class PostClassAnnouncementTool extends AbstractActionTool {

  private final AnnouncementService announcements;
  private final PermissionsHelper perms;

  public PostClassAnnouncementTool(
      ActionSupport actions, AnnouncementService announcements, PermissionsHelper perms) {
    super(actions);
    this.announcements = announcements;
    this.perms = perms;
  }

  @Override
  public String name() {
    return "post_class_announcement";
  }

  @Override
  public String description() {
    return "Post an announcement to a class; the class's parents are notified.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Class name", true)
        .str("body", "The announcement text", true)
        .bool("requiresAck", "Whether parents must acknowledge", false)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.ANNOUNCEMENT_SEND);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<SchoolClassResponse> clazz = actions.tools().clazz(ctx, Args.str(args, "classRef"));
    if (clazz.clarified()) {
      return clarify(clazz);
    }
    UUID classId = clazz.value().id();
    if (!perms.canSendAnnouncementToScope(AnnouncementScope.CLASS, classId.toString())) {
      return deniedKey("assistant.denied.class");
    }
    String body = Args.str(args, "body");
    if (body == null) {
      return reject(ToolResult.clarify(msg("assistant.announcement.body_required")));
    }
    boolean requiresAck = Args.bool(args, "requiresAck", false);

    ObjectNode resolved = newArgs();
    resolved.put("classId", classId.toString());
    resolved.put("body", body);
    resolved.put("requiresAck", requiresAck);

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("scope", "CLASS");
    impact.put("class", clazz.value().name());
    impact.put("body", body);

    return readyMsg(
        resolved,
        "assistant.action.post_class_announcement.summary",
        impact,
        1,
        clazz.value().name(),
        body);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    UUID classId = uuid(resolvedArgs, "classId");
    if (!perms.canSendAnnouncementToScope(AnnouncementScope.CLASS, classId.toString())) {
      return ToolResult.denied(msg("assistant.denied.class"));
    }
    Language language = "ar".equals(ctx.language().getLanguage()) ? Language.AR : Language.EN;
    CreateAnnouncementRequest request =
        new CreateAnnouncementRequest(
            AnnouncementScope.CLASS,
            classId,
            null,
            null,
            language,
            text(resolvedArgs, "body"),
            null,
            resolvedArgs.get("requiresAck").asBoolean(),
            null);
    return ToolResult.ok(announcements.create(ctx.schoolId(), ctx.userId(), request));
  }
}
