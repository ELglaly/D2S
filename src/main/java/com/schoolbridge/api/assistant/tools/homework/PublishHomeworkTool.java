package com.schoolbridge.api.assistant.tools.homework;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.schoolbridge.api.homework.HomeworkService;
import com.schoolbridge.api.homework.dto.HomeworkResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * TEACHER(author)/ADMIN — publish homework (fans out to parents). Mirrors {@code POST
 * /homework/{id}/publish}.
 */
@Component
public class PublishHomeworkTool extends AbstractActionTool {

  private final HomeworkService homework;
  private final PermissionsHelper perms;

  public PublishHomeworkTool(
      ActionSupport actions, HomeworkService homework, PermissionsHelper perms) {
    super(actions);
    this.homework = homework;
    this.perms = perms;
  }

  @Override
  public String name() {
    return "publish_homework";
  }

  @Override
  public String description() {
    return "Publish a draft homework item, notifying the class's parents.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Class the homework belongs to", true)
        .str("homeworkRef", "Homework subject/title", true)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.HOMEWORK_PUBLISH);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<SchoolClassResponse> clazz = actions.tools().clazz(ctx, Args.str(args, "classRef"));
    if (clazz.clarified()) {
      return clarify(clazz);
    }
    UUID classId = clazz.value().id();
    if (!actions.tools().teachesOrAdmin(ctx, classId)) {
      return deniedKey("assistant.denied.class");
    }
    Resolved<HomeworkResponse> hw =
        actions.tools().homework(classId, Args.str(args, "homeworkRef"));
    if (hw.clarified()) {
      return clarify(hw);
    }
    UUID homeworkId = hw.value().id();
    if (!ctx.isAdmin() && !perms.isHomeworkAuthor(homeworkId)) {
      return deniedKey("assistant.denied.class");
    }

    ObjectNode resolved = newArgs();
    resolved.put("homeworkId", homeworkId.toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("homework", hw.value().subject());
    impact.put("class", clazz.value().name());

    return readyMsg(
        resolved, "assistant.action.publish_homework.summary", impact, 1, hw.value().subject());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    UUID homeworkId = uuid(resolvedArgs, "homeworkId");
    if (!ctx.isAdmin() && !perms.isHomeworkAuthor(homeworkId)) {
      return ToolResult.denied(msg("assistant.denied.class"));
    }
    return ToolResult.ok(homework.publish(homeworkId));
  }
}
