package com.schoolbridge.api.assistant.tools.parents;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.classes.service.ParentStudentLinkService;
import com.schoolbridge.api.identity.UserRole;
import java.util.Set;
import org.springframework.stereotype.Component;

/** ADMIN — parent-student links for a student. Mirrors {@code GET /students/{id}/parent-links}. */
@Component
public class ListParentLinksTool implements ReadTool {

  private final ToolSupport support;
  private final ParentStudentLinkService links;

  public ListParentLinksTool(ToolSupport support, ParentStudentLinkService links) {
    this.support = support;
    this.links = links;
  }

  @Override
  public String name() {
    return "list_parent_links";
  }

  @Override
  public String description() {
    return "List the parents linked to a student and their relationship.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder().str("studentRef", "Student's full name", true).build();
  }

  @Override
  public Set<UserRole> roles() {
    return Set.of(UserRole.SCHOOL_ADMIN);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    Resolved<StudentResponse> student = support.student(ctx, Args.str(args, "studentRef"));
    if (student.clarified()) {
      return student.result();
    }
    return ToolResult.ok(links.listByStudent(student.value().id()));
  }
}
