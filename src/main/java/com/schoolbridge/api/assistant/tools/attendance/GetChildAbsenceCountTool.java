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
import com.schoolbridge.api.attendance.AttendanceStatus;
import com.schoolbridge.api.classes.dto.ParentChildResponse;
import com.schoolbridge.api.common.security.PermissionsHelper;
import com.schoolbridge.api.common.security.authz.Permission;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** PARENT — count of a child's absences within a month (defaults to the current month). */
@Component
public class GetChildAbsenceCountTool implements ReadTool {

  private final ToolSupport support;
  private final PermissionsHelper perms;
  private final AttendanceService attendance;

  public GetChildAbsenceCountTool(
      ToolSupport support, PermissionsHelper perms, AttendanceService attendance) {
    this.support = support;
    this.perms = perms;
    this.attendance = attendance;
  }

  @Override
  public String name() {
    return "get_child_absence_count";
  }

  @Override
  public String description() {
    return "Count a child's ABSENT days within a month (format YYYY-MM; defaults to this month).";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("childName", "Child's first or full name", false)
        .str("month", "Month as YYYY-MM; omit for the current month", false)
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
    YearMonth month = parseMonth(Args.str(args, "month"));
    LocalDate from = month.atDay(1);
    LocalDate to = month.atEndOfMonth();
    long absences =
        attendance.history(studentId, from, to).stream()
            .filter(e -> e.status() == AttendanceStatus.ABSENT)
            .count();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("studentId", studentId);
    result.put("from", from);
    result.put("to", to);
    result.put("absences", absences);
    return ToolResult.ok(result);
  }

  private YearMonth parseMonth(String raw) {
    if (raw == null) {
      return YearMonth.now();
    }
    try {
      return YearMonth.parse(raw);
    } catch (DateTimeParseException ex) {
      return YearMonth.now();
    }
  }
}
