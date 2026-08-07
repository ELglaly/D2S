package com.schoolbridge.api.assistant.tools.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.attendance.AttendanceService;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.common.security.authz.Permission;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** TEACHER/ADMIN — a student's attendance history. Mirrors {@code GET /attendance/history}. */
@Component
public class GetStudentAttendanceTool implements ReadTool {

  private static final int DEFAULT_RANGE_DAYS = 30;

  private final ToolSupport support;
  private final AttendanceService attendance;

  public GetStudentAttendanceTool(ToolSupport support, AttendanceService attendance) {
    this.support = support;
    this.attendance = attendance;
  }

  @Override
  public String name() {
    return "get_student_attendance";
  }

  @Override
  public String description() {
    return "Get a student's attendance over a date range (defaults to the last 30 days).";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("studentRef", "Student's full name", true)
        .date("from", "Range start date YYYY-MM-DD (inclusive)", false)
        .date("to", "Range end date YYYY-MM-DD (inclusive)", false)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.ATTENDANCE_READ);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    Resolved<StudentResponse> student = support.student(ctx, Args.str(args, "studentRef"));
    if (student.clarified()) {
      return student.result();
    }
    UUID studentId = student.value().id();
    LocalDate to = Args.date(args, "to");
    if (to == null) {
      to = LocalDate.now();
    }
    LocalDate from = Args.date(args, "from");
    if (from == null) {
      from = to.minusDays(DEFAULT_RANGE_DAYS);
    }
    return ToolResult.ok(attendance.history(studentId, from, to));
  }
}
