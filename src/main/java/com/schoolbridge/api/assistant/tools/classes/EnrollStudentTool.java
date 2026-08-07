package com.schoolbridge.api.assistant.tools.classes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.AbstractActionTool;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.dto.EnrollStudentRequest;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.classes.service.EnrollmentService;
import com.schoolbridge.api.common.security.authz.Permission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** ADMIN — enroll a student in a class. Mirrors {@code POST /classes/{classId}/enrollments}. */
@Component
public class EnrollStudentTool extends AbstractActionTool {

  private final EnrollmentService enrollments;

  public EnrollStudentTool(ActionSupport actions, EnrollmentService enrollments) {
    super(actions);
    this.enrollments = enrollments;
  }

  @Override
  public String name() {
    return "enroll_student";
  }

  @Override
  public String description() {
    return "Enroll a student in a class.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Class name", true)
        .str("studentRef", "Student's full name", true)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.ENROLLMENT_MANAGE);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<SchoolClassResponse> clazz = actions.tools().clazz(ctx, Args.str(args, "classRef"));
    if (clazz.clarified()) {
      return clarify(clazz);
    }
    Resolved<StudentResponse> student = actions.tools().student(ctx, Args.str(args, "studentRef"));
    if (student.clarified()) {
      return clarify(student);
    }
    ObjectNode resolved = newArgs();
    resolved.put("classId", clazz.value().id().toString());
    resolved.put("studentId", student.value().id().toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("student", student.value().fullName());
    impact.put("class", clazz.value().name());

    return readyMsg(
        resolved,
        "assistant.action.enroll_student.summary",
        impact,
        1,
        student.value().fullName(),
        clazz.value().name());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    return ToolResult.ok(
        enrollments.enroll(
            uuid(resolvedArgs, "classId"),
            new EnrollStudentRequest(uuid(resolvedArgs, "studentId"))));
  }
}
