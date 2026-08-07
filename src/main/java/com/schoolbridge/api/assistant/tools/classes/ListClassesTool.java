package com.schoolbridge.api.assistant.tools.classes;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.service.SchoolClassService;
import com.schoolbridge.api.common.security.authz.Permission;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** ADMIN — list all classes in the school. Mirrors {@code GET /classes}. */
@Component
public class ListClassesTool implements ReadTool {

  private static final int MAX = 200;

  private final SchoolClassService classes;

  public ListClassesTool(SchoolClassService classes) {
    this.classes = classes;
  }

  @Override
  public String name() {
    return "list_classes";
  }

  @Override
  public String description() {
    return "List all classes in the school.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.empty();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.CLASS_MANAGE);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    return ToolResult.ok(classes.list(ctx.schoolId(), PageRequest.of(0, MAX)).getContent());
  }
}
