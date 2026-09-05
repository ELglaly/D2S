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
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.subjects.dto.SubjectResponse;
import com.schoolbridge.api.subjects.service.SubjectService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** ADMIN â€” delete a subject (destructive). Mirrors {@code DELETE /subjects/{id}}. */
@Component
public class DeleteSubjectTool extends AbstractActionTool {

  private final SubjectService subjects;

  public DeleteSubjectTool(ActionSupport actions, SubjectService subjects) {
    super(actions);
    this.subjects = subjects;
  }

  @Override
  public String name() {
    return "delete_subject";
  }

  @Override
  public String description() {
    return "Permanently delete a subject.";
  }

  @Override
  public boolean destructive() {
    return true;
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder().str("subjectRef", "Subject name", true).build();
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
    ObjectNode resolved = newArgs();
    resolved.put("subjectId", subject.value().id().toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("subject", subject.value().name());
    impact.put("destructive", true);

    return readyMsg(
        resolved, "assistant.action.delete_subject.summary", impact, 1, subject.value().name());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    subjects.delete(uuid(resolvedArgs, "subjectId"));
    return ToolResult.ok(Map.of("deleted", true));
  }
}
