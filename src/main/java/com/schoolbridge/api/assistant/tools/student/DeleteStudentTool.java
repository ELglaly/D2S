package com.schoolbridge.api.assistant.tools.student;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.AbstractActionTool;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.classes.service.StudentService;
import com.schoolbridge.api.common.security.authz.Permission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ADMIN — delete a student (destructive; cascades enrollments + parent links). Mirrors {@code
 * DELETE /students/{id}}.
 */
@Component
public class DeleteStudentTool extends AbstractActionTool {

  private final StudentService students;

  public DeleteStudentTool(ActionSupport actions, StudentService students) {
    super(actions);
    this.students = students;
  }

  @Override
  public String name() {
    return "delete_student";
  }

  @Override
  public String description() {
    return "Permanently delete a student and their enrollments and parent links.";
  }

  @Override
  public boolean destructive() {
    return true;
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder().str("studentRef", "Student's full name", true).build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.STUDENT_MANAGE);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<StudentResponse> student = actions.tools().student(ctx, Args.str(args, "studentRef"));
    if (student.clarified()) {
      return clarify(student);
    }
    StudentResponse existing = student.value();
    ObjectNode resolved = newArgs();
    resolved.put("studentId", existing.id().toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("student", existing.fullName());
    impact.put("destructive", true);

    return readyMsg(
        resolved, "assistant.action.delete_student.summary", impact, 1, existing.fullName());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    students.delete(uuid(resolvedArgs, "studentId"));
    return ToolResult.ok(Map.of("deleted", true));
  }
}
