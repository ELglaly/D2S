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
import com.schoolbridge.api.classes.dto.EnrollmentResponse;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.classes.service.EnrollmentService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** ADMIN â€” remove a student's enrollment from a class. Mirrors {@code DELETE /enrollments/{id}}. */
@Component
public class RemoveEnrollmentTool extends AbstractActionTool {

  private final EnrollmentService enrollments;

  public RemoveEnrollmentTool(ActionSupport actions, EnrollmentService enrollments) {
    super(actions);
    this.enrollments = enrollments;
  }

  @Override
  public String name() {
    return "remove_enrollment";
  }

  @Override
  public String description() {
    return "Remove a student from a class.";
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
    UUID studentId = student.value().id();
    Optional<EnrollmentResponse> enrollment =
        enrollments.listByClass(clazz.value().id()).stream()
            .filter(e -> e.studentId().equals(studentId))
            .findFirst();
    if (enrollment.isEmpty()) {
      return reject(ToolResult.clarify(msg("assistant.enrollment.not_found")));
    }
    ObjectNode resolved = newArgs();
    resolved.put("enrollmentId", enrollment.get().id().toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("student", student.value().fullName());
    impact.put("class", clazz.value().name());

    return readyMsg(
        resolved,
        "assistant.action.remove_enrollment.summary",
        impact,
        1,
        student.value().fullName(),
        clazz.value().name());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    enrollments.delete(uuid(resolvedArgs, "enrollmentId"));
    return ToolResult.ok(Map.of("removed", true));
  }
}

