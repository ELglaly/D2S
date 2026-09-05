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
import com.schoolbridge.api.classes.dto.ParentChildResponse;
import com.schoolbridge.api.common.security.PermissionsHelper;
import com.schoolbridge.api.common.security.authz.Permission;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** PARENT â€” a child's attendance over a date range. Mirrors {@code GET /attendance/history}. */
@Component
public class GetChildAttendanceTool implements ReadTool {

  private static final int DEFAULT_RANGE_DAYS = 30;

  private final ToolSupport support;
  private final PermissionsHelper perms;
  private final AttendanceService attendance;

  public GetChildAttendanceTool(
      ToolSupport support, PermissionsHelper perms, AttendanceService attendance) {
    this.support = support;
    this.perms = perms;
    this.attendance = attendance;
  }

  @Override
  public String name() {
    return "get_child_attendance";
  }

  @Override
  public String description() {
    return "Get a child's attendance records over a date range (defaults to the last 30 days). "
        + "Omit childName if the parent has a single child.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("childName", "Child's first or full name", false)
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
    Resolved<ParentChildResponse> child = support.child(ctx, Args.str(args, "childName"));
    if (child.clarified()) {
      return child.result();
    }
    UUID studentId = child.value().studentId();
    if (!perms.parentLinkedTo(studentId)) {
      return support.denied("assistant.denied.child");
    }
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
