package com.schoolbridge.api.assistant.tools.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.subjects.dto.AssignSubjectToClassRequest;
import com.schoolbridge.api.subjects.dto.SubjectResponse;
import com.schoolbridge.api.subjects.service.ClassSubjectService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** ADMIN — add a subject to a class. Mirrors {@code POST /classes/{id}/subjects}. */
@Component
public class AddSubjectToClassTool extends AbstractActionTool {

  private final ClassSubjectService classSubjects;

  public AddSubjectToClassTool(ActionSupport actions, ClassSubjectService classSubjects) {
    super(actions);
    this.classSubjects = classSubjects;
  }

  @Override
  public String name() {
    return "add_subject_to_class";
  }

  @Override
  public String description() {
    return "Add (offer) a subject in a class.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Class name", true)
        .str("subjectRef", "Subject name", true)
        .build();
  }

  @Override
  public Set<UserRole> roles() {
    return Set.of(UserRole.SCHOOL_ADMIN);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<SchoolClassResponse> clazz = actions.tools().clazz(ctx, Args.str(args, "classRef"));
    if (clazz.clarified()) {
      return clarify(clazz);
    }
    Resolved<SubjectResponse> subject = actions.tools().subject(ctx, Args.str(args, "subjectRef"));
    if (subject.clarified()) {
      return clarify(subject);
    }
    ObjectNode resolved = newArgs();
    resolved.put("classId", clazz.value().id().toString());
    resolved.put("subjectId", subject.value().id().toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("class", clazz.value().name());
    impact.put("subject", subject.value().name());

    String en = "I'll add " + subject.value().name() + " to " + clazz.value().name() + ". Confirm?";
    String ar =
        "سأضيف مادة " + subject.value().name() + " إلى " + clazz.value().name() + ". أؤكّد؟";
    return ready(resolved, ar, en, impact, 1);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    return ToolResult.ok(
        classSubjects.assign(
            uuid(resolvedArgs, "classId"),
            new AssignSubjectToClassRequest(uuid(resolvedArgs, "subjectId"))));
  }
}
