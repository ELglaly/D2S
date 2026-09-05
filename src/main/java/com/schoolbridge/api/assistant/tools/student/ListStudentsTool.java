package com.schoolbridge.api.assistant.tools.student;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.classes.service.StudentService;
import com.schoolbridge.api.common.security.authz.Permission;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** ADMIN â€” list students, or look one up by name. Mirrors {@code GET /students}. */
@Component
public class ListStudentsTool implements ReadTool {

  private static final int MAX = 200;

  private final ToolSupport support;
  private final StudentService students;

  public ListStudentsTool(ToolSupport support, StudentService students) {
    this.support = support;
    this.students = students;
  }

  @Override
  public String name() {
    return "list_students";
  }

  @Override
  public String description() {
    return "List students in the school, or resolve a single student by name.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder().str("studentRef", "Optional student name to look up", false).build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.STUDENT_MANAGE);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    String ref = Args.str(args, "studentRef");
    if (ref != null) {
      Resolved<StudentResponse> student = support.student(ctx, ref);
      if (student.clarified()) {
        return student.result();
      }
      return ToolResult.ok(student.value());
    }
    List<StudentResponse> page = students.list(ctx.schoolId(), PageRequest.of(0, MAX)).getContent();
    return ToolResult.ok(page);
  }
}
