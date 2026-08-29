package com.schoolbridge.api.assistant.tools.classes;

import com.schoolbridge.api.common.security.authz.Permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.AbstractActionTool;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.service.SchoolClassService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ADMIN â€” delete a class (destructive; cascades enrollments + assignments). Mirrors {@code DELETE
 * /classes/{id}}.
 */
@Component
public class DeleteClassTool extends AbstractActionTool {

  private final SchoolClassService classes;

  public DeleteClassTool(ActionSupport actions, SchoolClassService classes) {
    super(actions);
    this.classes = classes;
  }

  @Override
  public String name() {
    return "delete_class";
  }

  @Override
  public String description() {
    return "Permanently delete a class and its enrollments and subject/teacher assignments.";
  }

  @Override
  public boolean destructive() {
    return true;
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder().str("classRef", "Class name", true).build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.CLASS_MANAGE);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<SchoolClassResponse> clazz = actions.tools().clazz(ctx, Args.str(args, "classRef"));
    if (clazz.clarified()) {
      return clarify(clazz);
    }
    ObjectNode resolved = newArgs();
    resolved.put("classId", clazz.value().id().toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("class", clazz.value().name());
    impact.put("destructive", true);

    return readyMsg(
        resolved, "assistant.action.delete_class.summary", impact, 1, clazz.value().name());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    classes.delete(uuid(resolvedArgs, "classId"));
    return ToolResult.ok(Map.of("deleted", true));
  }
}

