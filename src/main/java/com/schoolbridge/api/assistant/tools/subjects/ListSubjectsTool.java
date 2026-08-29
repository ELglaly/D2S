package com.schoolbridge.api.assistant.tools.subjects;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.subjects.service.SubjectService;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** ADMIN â€” list subjects in the school. Mirrors {@code GET /subjects}. */
@Component
public class ListSubjectsTool implements ReadTool {

  private static final int MAX = 200;

  private final SubjectService subjects;

  public ListSubjectsTool(SubjectService subjects) {
    this.subjects = subjects;
  }

  @Override
  public String name() {
    return "list_subjects";
  }

  @Override
  public String description() {
    return "List the subjects offered by the school.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.empty();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.SUBJECT_MANAGE);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    return ToolResult.ok(subjects.list(ctx.schoolId(), PageRequest.of(0, MAX)).getContent());
  }
}

