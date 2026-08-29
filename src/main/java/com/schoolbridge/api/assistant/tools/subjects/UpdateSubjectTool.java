package com.schoolbridge.api.assistant.tools.subjects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.AbstractActionTool;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.subjects.SubjectStatus;
import com.schoolbridge.api.subjects.dto.SubjectResponse;
import com.schoolbridge.api.subjects.dto.UpdateSubjectRequest;
import com.schoolbridge.api.subjects.service.SubjectService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** ADMIN â€” update a subject. Mirrors {@code PATCH /subjects/{id}}. */
@Component
public class UpdateSubjectTool extends AbstractActionTool {

  private final SubjectService subjects;

  public UpdateSubjectTool(ActionSupport actions, SubjectService subjects) {
    super(actions);
    this.subjects = subjects;
  }

  @Override
  public String name() {
    return "update_subject";
  }

  @Override
  public String description() {
    return "Update a subject's name, code, or description.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("subjectRef", "Current subject name", true)
        .str("name", "New name", false)
        .str("code", "New code", false)
        .str("description", "New description", false)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.SUBJECT_MANAGE);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<SubjectResponse> subject = actions.tools().subject(ctx, Args.str(args, "subjectRef"));
    if (subject.clarified()) {
      return clarify(subject);
    }
    SubjectResponse existing = subject.value();
    String name = Args.str(args, "name");
    name = name != null ? name : existing.name();
    String code = Args.str(args, "code");
    code = code != null ? code : existing.code();
    String description = Args.str(args, "description");
    description = description != null ? description : existing.description();

    ObjectNode resolved = newArgs();
    resolved.put("subjectId", existing.id().toString());
    resolved.put("name", name);
    if (code != null) {
      resolved.put("code", code);
    }
    if (description != null) {
      resolved.put("description", description);
    }
    resolved.put("status", existing.status().name());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("subject", existing.name());
    impact.put("newName", name);

    return readyMsg(
        resolved, "assistant.action.update_subject.summary", impact, 1, existing.name(), name);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    UpdateSubjectRequest request =
        new UpdateSubjectRequest(
            text(resolvedArgs, "name"),
            text(resolvedArgs, "code"),
            text(resolvedArgs, "description"),
            SubjectStatus.valueOf(text(resolvedArgs, "status")));
    return ToolResult.ok(subjects.update(uuid(resolvedArgs, "subjectId"), request));
  }
}

