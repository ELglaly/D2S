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
import com.schoolbridge.api.homework.HomeworkService;
import com.schoolbridge.api.homework.dto.HomeworkResponse;
import com.schoolbridge.api.homework.dto.UpdateHomeworkRequest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** TEACHER(author)/ADMIN â€” edit a homework item. Mirrors {@code PATCH /homework/{id}}. */
@Component
public class UpdateHomeworkTool extends AbstractActionTool {

  private final HomeworkService homework;
  private final PermissionsHelper perms;

  public UpdateHomeworkTool(
      ActionSupport actions, HomeworkService homework, PermissionsHelper perms) {
    super(actions);
    this.homework = homework;
    this.perms = perms;
  }

  @Override
  public String name() {
    return "update_homework";
  }

  @Override
  public String description() {
    return "Edit a homework item's subject, description, or due date.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Class the homework belongs to", true)
        .str("homeworkRef", "Homework subject/title to edit", true)
        .str("subject", "New subject/title", false)
        .str("description", "New description", false)
        .date("dueDate", "New due date YYYY-MM-DD (future)", false)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.HOMEWORK_UPDATE);
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
    HomeworkResponse item = hw.value();
    if (!ctx.isAdmin() && !perms.isHomeworkAuthor(item.id())) {
      return deniedKey("assistant.denied.class");
    }

    String subject = Args.str(args, "subject");
    String description = Args.str(args, "description");
    LocalDate dueDate = Args.date(args, "dueDate");
    String newSubject = subject != null ? subject : item.subject();
    String newDescription = description != null ? description : item.description();
    LocalDate newDueDate = dueDate != null ? dueDate : item.dueDate();

    ObjectNode resolved = newArgs();
    resolved.put("homeworkId", item.id().toString());
    resolved.put("subject", newSubject);
    resolved.put("description", newDescription);
    resolved.put("dueDate", newDueDate.toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("homework", item.subject());
    impact.put("newSubject", newSubject);
    impact.put("newDueDate", newDueDate.toString());

    return readyMsg(
        resolved,
        "assistant.action.update_homework.summary",
        impact,
        1,
        item.subject(),
        newSubject,
        newDueDate.toString());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    UUID homeworkId = uuid(resolvedArgs, "homeworkId");
    if (!ctx.isAdmin() && !perms.isHomeworkAuthor(homeworkId)) {
      return ToolResult.denied(msg("assistant.denied.class"));
    }
    UpdateHomeworkRequest request =
        new UpdateHomeworkRequest(
            text(resolvedArgs, "subject"),
            text(resolvedArgs, "description"),
            null,
            localDate(resolvedArgs, "dueDate"));
    return ToolResult.ok(homework.update(homeworkId, request));
  }
}

