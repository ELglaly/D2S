package com.schoolbridge.api.assistant.tools.announcements;

import com.schoolbridge.api.common.security.authz.Permission;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** ADMIN â€” post an announcement at any scope. Mirrors {@code POST /announcements}. */
@Component
public class PostAnnouncementTool extends AbstractActionTool {

  private final AnnouncementService announcements;
  private final PermissionsHelper perms;

  public PostAnnouncementTool(
      ActionSupport actions, AnnouncementService announcements, PermissionsHelper perms) {
    super(actions);
    this.announcements = announcements;
    this.perms = perms;
  }

  @Override
  public String name() {
    return "post_announcement";
  }

  @Override
  public String description() {
    return "Post an announcement to the whole school, a grade, or a class.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .enumStr("scope", "Targeting scope", true, List.of("SCHOOL", "GRADE", "CLASS"))
        .str("classRef", "Class name (for CLASS scope)", false)
        .str("gradeLevel", "Grade level (for GRADE scope)", false)
        .str("body", "The announcement text", true)
        .bool("requiresAck", "Whether parents must acknowledge", false)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.ANNOUNCEMENT_MANAGE);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    AnnouncementScope scope = parseScope(Args.str(args, "scope"));
    if (scope == null) {
      return reject(ToolResult.clarify(msg("assistant.announcement.scope_unsupported")));
    }
    String body = Args.str(args, "body");
    if (body == null) {
      return reject(ToolResult.clarify(msg("assistant.announcement.body_required")));
    }
    boolean requiresAck = Args.bool(args, "requiresAck", false);

    ObjectNode resolved = newArgs();
    resolved.put("scope", scope.name());
    resolved.put("body", body);
    resolved.put("requiresAck", requiresAck);
    String targetEn;
    String targetAr;
    if (scope == AnnouncementScope.CLASS) {
      Resolved<SchoolClassResponse> clazz = actions.tools().clazz(ctx, Args.str(args, "classRef"));
      if (clazz.clarified()) {
        return clarify(clazz);
      }
      resolved.put("classId", clazz.value().id().toString());
      targetEn = clazz.value().name();
      targetAr = clazz.value().name();
    } else if (scope == AnnouncementScope.GRADE) {
      String grade = Args.str(args, "gradeLevel");
      if (grade == null) {
        return reject(ToolResult.clarify(msg("assistant.announcement.scope_unsupported")));
      }
      resolved.put("gradeLevel", grade);
      targetEn = grade;
      targetAr = grade;
    } else {
      targetEn = msgIn(EN, "assistant.action.post_announcement.target.school");
      targetAr = msgIn(AR, "assistant.action.post_announcement.target.school");
    }
    if (!perms.canSendAnnouncementToScope(scope, resolved.path("classId").asText(null))) {
      return deniedKey("assistant.denied.admin");
    }

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("scope", scope.name());
    impact.put("target", targetEn);
    impact.put("body", body);

    String en = msgIn(EN, "assistant.action.post_announcement.summary", targetEn, body);
    String ar = msgIn(AR, "assistant.action.post_announcement.summary", targetAr, body);
    return ready(resolved, ar, en, impact, 1);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    AnnouncementScope scope = AnnouncementScope.valueOf(text(resolvedArgs, "scope"));
    String classIdText = text(resolvedArgs, "classId");
    if (!perms.canSendAnnouncementToScope(scope, classIdText)) {
      return ToolResult.denied(msg("assistant.denied.admin"));
    }
    Language language = "ar".equals(ctx.language().getLanguage()) ? Language.AR : Language.EN;
    CreateAnnouncementRequest request =
        new CreateAnnouncementRequest(
            scope,
            classIdText == null ? null : UUID.fromString(classIdText),
            text(resolvedArgs, "gradeLevel"),
            null,
            language,
            text(resolvedArgs, "body"),
            null,
            resolvedArgs.get("requiresAck").asBoolean(),
            null);
    return ToolResult.ok(announcements.create(ctx.schoolId(), ctx.userId(), request));
  }

  private static AnnouncementScope parseScope(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      AnnouncementScope scope = AnnouncementScope.valueOf(raw.toUpperCase(Locale.ROOT));
      return scope == AnnouncementScope.CUSTOM ? null : scope;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}

