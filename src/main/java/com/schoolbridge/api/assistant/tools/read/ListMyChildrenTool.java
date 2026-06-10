package com.schoolbridge.api.assistant.tools.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.service.ParentChildrenService;
import com.schoolbridge.api.identity.UserRole;
import java.util.Set;
import org.springframework.stereotype.Component;

/** PARENT — lists the caller's linked children. Mirrors {@code GET /parents/me/children}. */
@Component
public class ListMyChildrenTool implements ReadTool {

  private final ParentChildrenService parentChildren;

  public ListMyChildrenTool(ParentChildrenService parentChildren) {
    this.parentChildren = parentChildren;
  }

  @Override
  public String name() {
    return "list_my_children";
  }

  @Override
  public String description() {
    return "List the caller's linked children with their class and status.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.empty();
  }

  @Override
  public Set<UserRole> roles() {
    return Set.of(UserRole.PARENT);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    return ToolResult.ok(parentChildren.listChildren(ctx.userId()));
  }
}
