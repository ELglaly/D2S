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
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.subjects.dto.ClassSubjectResponse;
import com.schoolbridge.api.subjects.dto.SubjectResponse;
import com.schoolbridge.api.subjects.service.ClassSubjectService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** ADMIN — remove a subject from a class. Mirrors {@code DELETE /class-subjects/{id}}. */
@Component
public class RemoveClassSubjectTool extends AbstractActionTool {

  private static final int MAX = 200;

  private final ClassSubjectService classSubjects;

  public RemoveClassSubjectTool(ActionSupport actions, ClassSubjectService classSubjects) {
    super(actions);
    this.classSubjects = classSubjects;
  }

  @Override
  public String name() {
    return "remove_class_subject";
  }

  @Override
  public String description() {
    return "Remove a subject from a class.";
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
    UUID subjectId = subject.value().id();
    Optional<ClassSubjectResponse> classSubject =
        classSubjects.listByClass(clazz.value().id(), PageRequest.of(0, MAX)).getContent().stream()
            .filter(cs -> cs.subjectId().equals(subjectId))
            .findFirst();
    if (classSubject.isEmpty()) {
      return reject(ToolResult.clarify(msg("assistant.class_subject.not_found")));
    }
    ObjectNode resolved = newArgs();
    resolved.put("classSubjectId", classSubject.get().id().toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("class", clazz.value().name());
    impact.put("subject", subject.value().name());

    return readyMsg(
        resolved,
        "assistant.action.remove_class_subject.summary",
        impact,
        1,
        subject.value().name(),
        clazz.value().name());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    classSubjects.delete(uuid(resolvedArgs, "classSubjectId"));
    return ToolResult.ok(Map.of("removed", true));
  }
}
