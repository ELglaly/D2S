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
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.identity.dto.UserResponse;
import com.schoolbridge.api.subjects.dto.SubjectResponse;
import com.schoolbridge.api.subjects.dto.TeacherSubjectAssignmentResponse;
import com.schoolbridge.api.subjects.service.TeacherSubjectAssignmentService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ADMIN — unassign a teacher from a subject in a class. Mirrors {@code DELETE
 * .../teacher-assignments/{id}}.
 */
@Component
public class RemoveTeacherAssignmentTool extends AbstractActionTool {

  private final TeacherSubjectAssignmentService assignments;

  public RemoveTeacherAssignmentTool(
      ActionSupport actions, TeacherSubjectAssignmentService assignments) {
    super(actions);
    this.assignments = assignments;
  }

  @Override
  public String name() {
    return "remove_teacher_assignment";
  }

  @Override
  public String description() {
    return "Remove a teacher's assignment to a subject in a class.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Class name", true)
        .str("subjectRef", "Subject name", true)
        .str("teacherRef", "Teacher's name", true)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.SUBJECT_MANAGE);
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
    Resolved<UserResponse> teacher = actions.tools().teacher(ctx, Args.str(args, "teacherRef"));
    if (teacher.clarified()) {
      return clarify(teacher);
    }
    UUID teacherUserId = teacher.value().id();
    Optional<TeacherSubjectAssignmentResponse> assignment =
        assignments.listByClassAndSubject(clazz.value().id(), subject.value().id()).stream()
            .filter(a -> a.teacherUserId().equals(teacherUserId))
            .findFirst();
    if (assignment.isEmpty()) {
      return reject(ToolResult.clarify(msg("assistant.teacher_assignment.not_found")));
    }
    ObjectNode resolved = newArgs();
    resolved.put("assignmentId", assignment.get().id().toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("teacher", teacher.value().name());
    impact.put("subject", subject.value().name());
    impact.put("class", clazz.value().name());

    return readyMsg(
        resolved,
        "assistant.action.remove_teacher_assignment.summary",
        impact,
        1,
        teacher.value().name(),
        subject.value().name(),
        clazz.value().name());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    assignments.delete(uuid(resolvedArgs, "assignmentId"));
    return ToolResult.ok(Map.of("removed", true));
  }
}
