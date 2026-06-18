package com.schoolbridge.api.assistant.tools.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.AbstractActionTool;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.attendance.AttendanceService;
import com.schoolbridge.api.attendance.dto.MarkAllPresentRequest;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.service.EnrollmentService;
import com.schoolbridge.api.identity.UserRole;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * TEACHER/ADMIN — mark every enrolled student present. Mirrors {@code POST
 * /attendance/mark-all-present}.
 */
@Component
public class MarkAllPresentTool extends AbstractActionTool {

  private final AttendanceService attendance;
  private final EnrollmentService enrollments;

  public MarkAllPresentTool(
      ActionSupport actions, AttendanceService attendance, EnrollmentService enrollments) {
    super(actions);
    this.attendance = attendance;
    this.enrollments = enrollments;
  }

  @Override
  public String name() {
    return "mark_all_present";
  }

  @Override
  public String description() {
    return "Mark all enrolled students in a class PRESENT for a date (skips students already marked).";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Class name", true)
        .date("date", "Date YYYY-MM-DD; defaults to today", false)
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
    LocalDate date = Args.date(args, "date");
    if (date == null) {
      date = LocalDate.now();
    }
    int count = enrollments.listByClass(classId).size();

    ObjectNode resolved = newArgs();
    resolved.put("classId", classId.toString());
    resolved.put("date", date.toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("class", clazz.value().name());
    impact.put("date", date.toString());
    impact.put("studentCount", count);

    return readyMsg(
        resolved,
        "assistant.action.mark_all_present.summary",
        impact,
        count,
        String.valueOf(count),
        clazz.value().name(),
        date.toString());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    UUID classId = uuid(resolvedArgs, "classId");
    if (!actions.tools().teachesOrAdmin(ctx, classId)) {
      return ToolResult.denied(msg("assistant.denied.class"));
    }
    return ToolResult.ok(
        attendance.markAllPresent(
            ctx.userId(), new MarkAllPresentRequest(classId, localDate(resolvedArgs, "date"))));
  }
}
