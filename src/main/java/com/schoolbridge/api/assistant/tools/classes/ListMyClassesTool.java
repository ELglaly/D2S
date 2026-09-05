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

/** TEACHER â€” lists classes the caller teaches. Mirrors {@code GET /classes/my-classes}. */
@Component
public class ListMyClassesTool implements ReadTool {

  private static final int MAX = 200;

  private final SchoolClassService classes;

  public ListMyClassesTool(SchoolClassService classes) {
    this.classes = classes;
  }

  @Override
  public String name() {
    return "list_my_classes";
  }

  @Override
  public String description() {
    return "List the classes the calling teacher is assigned to.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.empty();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.CLASS_READ);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    return ToolResult.ok(classes.listMyClasses(ctx.userId(), PageRequest.of(0, MAX)).getContent());
  }
}
