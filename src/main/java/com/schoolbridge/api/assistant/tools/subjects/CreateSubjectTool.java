package com.schoolbridge.api.assistant.tools.subjects;

import com.schoolbridge.api.common.security.authz.Permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.AbstractActionTool;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.subjects.dto.CreateSubjectRequest;
import com.schoolbridge.api.subjects.service.SubjectService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** ADMIN â€” create a subject. Mirrors {@code POST /subjects}. */
@Component
public class CreateSubjectTool extends AbstractActionTool {

  private final SubjectService subjects;

  public CreateSubjectTool(ActionSupport actions, SubjectService subjects) {
    super(actions);
    this.subjects = subjects;
  }

  @Override
  public String name() {
    return "create_subject";
  }

  @Override
  public String description() {
    return "Create a new subject for the school.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("name", "Subject name", true)
        .str("code", "Optional subject code", false)
        .str("description", "Optional description", false)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.SUBJECT_MANAGE);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    String name = Args.str(args, "name");
    if (name == null) {
      return reject(ToolResult.clarify(msg("assistant.subject.name_required")));
    }
    ObjectNode resolved = newArgs();
    resolved.put("name", name);
    if (Args.str(args, "code") != null) {
      resolved.put("code", Args.str(args, "code"));
    }
    if (Args.str(args, "description") != null) {
      resolved.put("description", Args.str(args, "description"));
    }

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("name", name);

    return readyMsg(resolved, "assistant.action.create_subject.summary", impact, 1, name);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    CreateSubjectRequest request =
        new CreateSubjectRequest(
            text(resolvedArgs, "name"),
            text(resolvedArgs, "code"),
            text(resolvedArgs, "description"));
    return ToolResult.ok(subjects.create(ctx.schoolId(), request));
  }
}

