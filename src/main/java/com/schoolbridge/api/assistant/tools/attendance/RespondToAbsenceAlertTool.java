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
import com.schoolbridge.api.attendance.dto.AttendanceHistoryEntry;
import com.schoolbridge.api.attendance.dto.ParentResponseRequest;
import com.schoolbridge.api.classes.dto.ParentChildResponse;
import com.schoolbridge.api.common.security.authz.Permission;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * PARENT — reply to an absence/late/excused alert. Mirrors {@code POST
 * /attendance/{id}/parent-response}.
 */
@Component
public class RespondToAbsenceAlertTool extends AbstractActionTool {

  private final AttendanceService attendance;

  public RespondToAbsenceAlertTool(ActionSupport actions, AttendanceService attendance) {
    super(actions);
    this.attendance = attendance;
  }

  @Override
  public String name() {
    return "respond_to_absence_alert";
  }

  @Override
  public String description() {
    return "Send the school a reason/explanation for a child's absence, lateness, or excused day.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("childName", "Child's first or full name", false)
        .date("date", "The day in question YYYY-MM-DD; defaults to yesterday", false)
        .str("response", "The explanation to send to the school", true)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.ATTENDANCE_RECORD);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<ParentChildResponse> child = actions.tools().child(ctx, Args.str(args, "childName"));
    if (child.clarified()) {
      return clarify(child);
    }
    String response = Args.str(args, "response");
    if (response == null) {
      return reject(ToolResult.clarify(msg("assistant.response_required")));
    }
    LocalDate date = Args.date(args, "date");
    if (date == null) {
      date = LocalDate.now().minusDays(1);
    }
    UUID studentId = child.value().studentId();
    Optional<AttendanceHistoryEntry> record =
        attendance.history(studentId, date, date).stream()
            .filter(e -> e.status() != AttendanceStatus.PRESENT)
            .findFirst();
    if (record.isEmpty()) {
      return reject(ToolResult.clarify(msg("assistant.attendance.record_not_found")));
    }

    ObjectNode resolved = newArgs();
    resolved.put("recordId", record.get().recordId().toString());
    resolved.put("response", response);

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("child", child.value().fullName());
    impact.put("date", date.toString());
    impact.put("response", response);

    return readyMsg(
        resolved,
        "assistant.action.respond_to_absence_alert.summary",
        impact,
        1,
        child.value().fullName(),
        date.toString(),
        response);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    UUID recordId = uuid(resolvedArgs, "recordId");
    String response = text(resolvedArgs, "response");
    return ToolResult.ok(
        attendance.recordParentResponse(
            recordId, ctx.userId(), new ParentResponseRequest(response)));
  }
}
