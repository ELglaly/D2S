package com.schoolbridge.api.assistant.tools.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.homework.HomeworkService;
import com.schoolbridge.api.homework.dto.CreateHomeworkRequest;
import com.schoolbridge.api.identity.UserRole;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** TEACHER/ADMIN — create homework (draft or published). Mirrors {@code POST /homework}. */
@Component
public class CreateHomeworkTool extends AbstractActionTool {

  private final HomeworkService homework;

  public CreateHomeworkTool(ActionSupport actions, HomeworkService homework) {
    super(actions);
    this.homework = homework;
  }

  @Override
  public String name() {
    return "create_homework";
  }

  @Override
  public String description() {
    return "Create a homework item for a class; optionally publish it (which notifies parents).";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Class name", true)
        .str("subject", "Homework subject/title", true)
        .str("description", "What the homework is", true)
        .date("dueDate", "Due date YYYY-MM-DD (must be in the future)", true)
        .bool("requiresAck", "Whether parents must acknowledge", false)
        .bool("publish", "Publish immediately (notifies parents) instead of saving as draft", false)
        .build();
  }

  @Override
  public Set<UserRole> roles() {
    return Set.of(UserRole.TEACHER, UserRole.SCHOOL_ADMIN);
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
    String subject = Args.str(args, "subject");
    String description = Args.str(args, "description");
    LocalDate dueDate = Args.date(args, "dueDate");
    if (subject == null || description == null || dueDate == null) {
      return reject(ToolResult.clarify(msg("assistant.homework.fields_required")));
    }
    boolean requiresAck = Args.bool(args, "requiresAck", false);
    boolean publish = Args.bool(args, "publish", false);

    ObjectNode resolved = newArgs();
    resolved.put("classId", classId.toString());
    resolved.put("subject", subject);
    resolved.put("description", description);
    resolved.put("dueDate", dueDate.toString());
    resolved.put("requiresAck", requiresAck);
    resolved.put("publish", publish);

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("class", clazz.value().name());
    impact.put("subject", subject);
    impact.put("dueDate", dueDate.toString());
    impact.put("publish", publish);

    String state = publish ? " and publish it (parents are notified)" : " as a draft";
    String stateAr = publish ? " ونشره (سيُخطَر أولياء الأمور)" : " كمسودة";
    String en =
        "I'll create homework \""
            + subject
            + "\" for "
            + clazz.value().name()
            + " due "
            + dueDate
            + state
            + ". Confirm?";
    String ar =
        "سأنشئ واجب «"
            + subject
            + "» للصف "
            + clazz.value().name()
            + " بموعد تسليم "
            + dueDate
            + stateAr
            + ". أؤكّد؟";
    return ready(resolved, ar, en, impact, 1);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    UUID classId = uuid(resolvedArgs, "classId");
    if (!actions.tools().teachesOrAdmin(ctx, classId)) {
      return ToolResult.denied(msg("assistant.denied.class"));
    }
    CreateHomeworkRequest request =
        new CreateHomeworkRequest(
            classId,
            text(resolvedArgs, "subject"),
            text(resolvedArgs, "description"),
            null,
            localDate(resolvedArgs, "dueDate"),
            resolvedArgs.get("requiresAck").asBoolean(),
            resolvedArgs.get("publish").asBoolean());
    return ToolResult.ok(homework.create(ctx.schoolId(), ctx.userId(), request));
  }
}
