package com.schoolbridge.api.assistant.tools.grades;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.classes.dto.ParentChildResponse;
import com.schoolbridge.api.common.security.PermissionsHelper;
import com.schoolbridge.api.grades.GradeService;
import com.schoolbridge.api.grades.dto.GradeRecordResponse;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * PARENT â€” a child's grades, optionally filtered by subject. Mirrors {@code GET /grades?studentId}.
 */
@Component
public class GetChildGradesTool implements ReadTool {

  private final ToolSupport support;
  private final PermissionsHelper perms;
  private final GradeService grades;

  public GetChildGradesTool(ToolSupport support, PermissionsHelper perms, GradeService grades) {
    this.support = support;
    this.perms = perms;
    this.grades = grades;
  }

  @Override
  public String name() {
    return "get_child_grades";
  }

  @Override
  public String description() {
    return "List a child's grades, optionally narrowed to a subject.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("childName", "Child's first or full name", false)
        .str("subject", "Optional subject filter", false)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.GRADE_READ);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    Resolved<ParentChildResponse> child = support.child(ctx, Args.str(args, "childName"));
    if (child.clarified()) {
      return child.result();
    }
    UUID studentId = child.value().studentId();
    if (!perms.parentLinkedTo(studentId)) {
      return support.denied("assistant.denied.child");
    }
    List<GradeRecordResponse> result = grades.listByStudent(studentId);
    String subject = Args.str(args, "subject");
    if (subject != null) {
      String needle = subject.toLowerCase(Locale.ROOT);
      result =
          result.stream()
              .filter(
                  g -> g.subject() != null && g.subject().toLowerCase(Locale.ROOT).contains(needle))
              .toList();
    }
    return ToolResult.ok(result);
  }
}

