package com.schoolbridge.api.assistant.tools.grades;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.grades.GradeService;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** TEACHER/ADMIN — grades for a class. Mirrors {@code GET /grades?classId}. */
@Component
public class GetClassGradesTool implements ReadTool {

  private static final int MAX = 200;

  private final ToolSupport support;
  private final GradeService grades;

  public GetClassGradesTool(ToolSupport support, GradeService grades) {
    this.support = support;
    this.grades = grades;
  }

  @Override
  public String name() {
    return "get_class_grades";
  }

  @Override
  public String description() {
    return "List grades recorded for a class.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder().str("classRef", "Class name", true).build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.GRADE_READ);
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
    return ToolResult.ok(grades.listByClass(classId, PageRequest.of(0, MAX)).getContent());
  }
}
