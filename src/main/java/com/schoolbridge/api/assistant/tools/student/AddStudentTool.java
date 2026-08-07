package com.schoolbridge.api.assistant.tools.student;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.AbstractActionTool;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.dto.CreateStudentRequest;
import com.schoolbridge.api.classes.service.StudentService;
import com.schoolbridge.api.common.security.authz.Permission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** ADMIN — add a student to the school. Mirrors {@code POST /students}. */
@Component
public class AddStudentTool extends AbstractActionTool {

  private final StudentService students;

  public AddStudentTool(ActionSupport actions, StudentService students) {
    super(actions);
    this.students = students;
  }

  @Override
  public String name() {
    return "add_student";
  }

  @Override
  public String description() {
    return "Create a new student record in the school.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("fullName", "Student's full name", true)
        .date("dateOfBirth", "Date of birth YYYY-MM-DD", false)
        .str("externalId", "Optional external/student ID", false)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.STUDENT_MANAGE);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    String fullName = Args.str(args, "fullName");
    if (fullName == null) {
      return reject(ToolResult.clarify(msg("assistant.student.name_required")));
    }
    ObjectNode resolved = newArgs();
    resolved.put("fullName", fullName);
    if (Args.date(args, "dateOfBirth") != null) {
      resolved.put("dateOfBirth", Args.date(args, "dateOfBirth").toString());
    }
    if (Args.str(args, "externalId") != null) {
      resolved.put("externalId", Args.str(args, "externalId"));
    }

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("fullName", fullName);

    return readyMsg(resolved, "assistant.action.add_student.summary", impact, 1, fullName);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    CreateStudentRequest request =
        new CreateStudentRequest(
            text(resolvedArgs, "fullName"),
            localDate(resolvedArgs, "dateOfBirth"),
            text(resolvedArgs, "externalId"));
    return ToolResult.ok(students.create(ctx.schoolId(), request));
  }
}
