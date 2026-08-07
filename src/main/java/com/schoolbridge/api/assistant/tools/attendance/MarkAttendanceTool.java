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
import com.schoolbridge.api.attendance.AttendanceStatus;
import com.schoolbridge.api.attendance.dto.MarkAttendanceRequest;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.common.security.authz.Permission;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** TEACHER/ADMIN — mark one student's attendance. Mirrors {@code POST /attendance/mark}. */
@Component
public class MarkAttendanceTool extends AbstractActionTool {

  private final AttendanceService attendance;

  public MarkAttendanceTool(ActionSupport actions, AttendanceService attendance) {
    super(actions);
    this.attendance = attendance;
  }

  @Override
  public String name() {
    return "mark_attendance";
  }

  @Override
  public String description() {
    return "Mark a single student PRESENT, ABSENT, LATE, or EXCUSED in a class on a date.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Class name", true)
        .str("studentRef", "Student's full name", true)
        .date("date", "Date YYYY-MM-DD; defaults to today", false)
        .enumStr(
            "status", "Attendance status", true, List.of("PRESENT", "ABSENT", "LATE", "EXCUSED"))
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.ATTENDANCE_RECORD);
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
    Resolved<StudentResponse> student = actions.tools().student(ctx, Args.str(args, "studentRef"));
    if (student.clarified()) {
      return clarify(student);
    }
    AttendanceStatus status = parseStatus(Args.str(args, "status"));
    if (status == null) {
      return reject(ToolResult.clarify(msg("assistant.attendance.status_required")));
    }
    LocalDate date = Args.date(args, "date");
    if (date == null) {
      date = LocalDate.now();
    }

    ObjectNode resolved = newArgs();
    resolved.put("classId", classId.toString());
    resolved.put("studentId", student.value().id().toString());
    resolved.put("date", date.toString());
    resolved.put("status", status.name());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("class", clazz.value().name());
    impact.put("student", student.value().fullName());
    impact.put("date", date.toString());
    impact.put("status", status.name());

    return readyMsg(
        resolved,
        "assistant.action.mark_attendance.summary",
        impact,
        1,
        student.value().fullName(),
        status.name(),
        clazz.value().name(),
        date.toString());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    UUID classId = uuid(resolvedArgs, "classId");
    if (!actions.tools().teachesOrAdmin(ctx, classId)) {
      return ToolResult.denied(msg("assistant.denied.class"));
    }
    MarkAttendanceRequest request =
        new MarkAttendanceRequest(
            uuid(resolvedArgs, "studentId"),
            classId,
            localDate(resolvedArgs, "date"),
            AttendanceStatus.valueOf(text(resolvedArgs, "status")));
    return ToolResult.ok(attendance.mark(ctx.userId(), request));
  }

  private static AttendanceStatus parseStatus(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return AttendanceStatus.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
