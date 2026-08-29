package com.schoolbridge.api.assistant.tools.attendance;

import com.schoolbridge.api.common.security.authz.Permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.attendance.AttendanceService;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * TEACHER/ADMIN â€” attendance roster for a class on a date. Mirrors {@code GET /attendance/roster}.
 */
@Component
public class GetClassAttendanceTool implements ReadTool {

  private final ToolSupport support;
  private final AttendanceService attendance;

  public GetClassAttendanceTool(ToolSupport support, AttendanceService attendance) {
    this.support = support;
    this.attendance = attendance;
  }

  @Override
  public String name() {
    return "get_class_attendance";
  }

  @Override
  public String description() {
    return "Get the attendance roster for a class on a date (defaults to today).";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Class name", true)
        .date("date", "Date YYYY-MM-DD; defaults to today", false)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.ATTENDANCE_READ);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    Resolved<SchoolClassResponse> clazz = support.clazz(ctx, Args.str(args, "classRef"));
    if (clazz.clarified()) {
      return clazz.result();
    }
    UUID classId = clazz.value().id();
    if (!support.teachesOrAdmin(ctx, classId)) {
      return support.denied("assistant.denied.class");
    }
    LocalDate date = Args.date(args, "date");
    if (date == null) {
      date = LocalDate.now();
    }
    return ToolResult.ok(attendance.roster(classId, date));
  }
}

