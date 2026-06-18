package com.schoolbridge.api.assistant.tools.classes;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.service.EnrollmentService;
import com.schoolbridge.api.identity.UserRole;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** TEACHER/ADMIN — students enrolled in a class. Mirrors {@code GET /classes/{id}/enrollments}. */
@Component
public class GetClassEnrollmentsTool implements ReadTool {

  private final ToolSupport support;
  private final EnrollmentService enrollments;

  public GetClassEnrollmentsTool(ToolSupport support, EnrollmentService enrollments) {
    this.support = support;
    this.enrollments = enrollments;
  }

  @Override
  public String name() {
    return "get_class_enrollments";
  }

  @Override
  public String description() {
    return "List the students enrolled in a class.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder().str("classRef", "Class name", true).build();
  }

  @Override
  public Set<UserRole> roles() {
    return Set.of(UserRole.TEACHER, UserRole.SCHOOL_ADMIN);
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
    return ToolResult.ok(enrollments.listByClass(classId));
  }
}
