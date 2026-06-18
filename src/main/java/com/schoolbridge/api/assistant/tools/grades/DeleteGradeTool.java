package com.schoolbridge.api.assistant.tools.grades;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.AbstractActionTool;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.NameMatching;
import com.schoolbridge.api.assistant.tools.support.NameMatching.MatchResult;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.grades.GradeService;
import com.schoolbridge.api.grades.dto.GradeRecordResponse;
import com.schoolbridge.api.identity.UserRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** ADMIN — delete a grade (destructive). Mirrors {@code DELETE /grades/{id}}. */
@Component
public class DeleteGradeTool extends AbstractActionTool {

  private final GradeService grades;

  public DeleteGradeTool(ActionSupport actions, GradeService grades) {
    super(actions);
    this.grades = grades;
  }

  @Override
  public String name() {
    return "delete_grade";
  }

  @Override
  public String description() {
    return "Permanently delete a student's grade.";
  }

  @Override
  public boolean destructive() {
    return true;
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("studentRef", "Student's full name", true)
        .str("subject", "Subject of the grade to delete", true)
        .str("period", "Term/period to disambiguate", false)
        .build();
  }

  @Override
  public Set<UserRole> roles() {
    return Set.of(UserRole.SCHOOL_ADMIN);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<StudentResponse> student = actions.tools().student(ctx, Args.str(args, "studentRef"));
    if (student.clarified()) {
      return clarify(student);
    }
    String subject = Args.str(args, "subject");
    if (subject == null) {
      return reject(ToolResult.clarify(msg("assistant.grade.subject_required")));
    }
    List<GradeRecordResponse> candidates = grades.listByStudent(student.value().id());
    String period = Args.str(args, "period");
    if (period != null) {
      candidates = candidates.stream().filter(g -> period.equalsIgnoreCase(g.period())).toList();
    }
    MatchResult<GradeRecordResponse> match =
        NameMatching.match(subject, candidates, GradeRecordResponse::subject);
    if (match.none()) {
      return reject(ToolResult.clarify(msg("assistant.grade.not_found", subject)));
    }
    if (match.ambiguous()) {
      return reject(ToolResult.clarify(msg("assistant.grade.ambiguous", subject)));
    }
    GradeRecordResponse grade = match.first();

    ObjectNode resolved = newArgs();
    resolved.put("gradeId", grade.id().toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("student", student.value().fullName());
    impact.put("subject", grade.subject());
    impact.put("destructive", true);

    return readyMsg(
        resolved,
        "assistant.action.delete_grade.summary",
        impact,
        1,
        student.value().fullName(),
        grade.subject());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    grades.delete(uuid(resolvedArgs, "gradeId"));
    return ToolResult.ok(Map.of("deleted", true));
  }
}
