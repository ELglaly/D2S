package com.schoolbridge.api.assistant.tools.grades;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.grades.GradeService;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** TEACHER/ADMIN — a student's grades. Mirrors {@code GET /grades?studentId}. */
@Component
public class GetStudentGradesTool implements ReadTool {

  private final ToolSupport support;
  private final GradeService grades;

  public GetStudentGradesTool(ToolSupport support, GradeService grades) {
    this.support = support;
    this.grades = grades;
  }

  @Override
  public String name() {
    return "get_student_grades";
  }

  @Override
  public String description() {
    return "List a student's grades across subjects.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder().str("studentRef", "Student's full name", true).build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.GRADE_READ);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    Resolved<StudentResponse> student = support.student(ctx, Args.str(args, "studentRef"));
    if (student.clarified()) {
      return student.result();
    }
    UUID studentId = student.value().id();
    return ToolResult.ok(grades.listByStudent(studentId));
  }
}
