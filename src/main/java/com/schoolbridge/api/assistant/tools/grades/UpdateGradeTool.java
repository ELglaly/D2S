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
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.grades.GradeService;
import com.schoolbridge.api.grades.dto.GradeRecordResponse;
import com.schoolbridge.api.grades.dto.UpdateGradeRequest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * TEACHER/ADMIN â€” change an existing grade. Mirrors {@code PATCH /grades/{id}} which is
 * role-guarded only; per Â§11.4 we re-derive the grade's class and re-run {@code teacherTeaches}
 * before mutating.
 */
@Component
public class UpdateGradeTool extends AbstractActionTool {

  private final GradeService grades;

  public UpdateGradeTool(ActionSupport actions, GradeService grades) {
    super(actions);
    this.grades = grades;
  }

  @Override
  public String name() {
    return "update_grade";
  }

  @Override
  public String description() {
    return "Change a student's existing grade (score, letter, or notes).";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("studentRef", "Student's full name", true)
        .str("subject", "Subject of the grade to change", true)
        .str("period", "Term/period to disambiguate", false)
        .number("score", "New numeric score", false)
        .str("gradeLabel", "New letter grade", false)
        .str("notes", "New notes", false)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.GRADE_UPDATE);
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
    if (!actions.tools().teachesOrAdmin(ctx, grade.classId())) {
      return deniedKey("assistant.denied.class");
    }

    Double score = Args.number(args, "score");
    String gradeLabel = Args.str(args, "gradeLabel");
    String notes = Args.str(args, "notes");
    BigDecimal newScore = score != null ? BigDecimal.valueOf(score) : grade.score();
    String newLabel = gradeLabel != null ? gradeLabel : grade.gradeLabel();
    String newNotes = notes != null ? notes : grade.notes();

    ObjectNode resolved = newArgs();
    resolved.put("gradeId", grade.id().toString());
    resolved.put("classId", grade.classId().toString());
    if (newScore != null) {
      resolved.put("score", newScore.toPlainString());
    }
    if (newLabel != null) {
      resolved.put("gradeLabel", newLabel);
    }
    if (newNotes != null) {
      resolved.put("notes", newNotes);
    }

    String value = newScore != null ? newScore.toPlainString() : newLabel;
    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("student", student.value().fullName());
    impact.put("subject", grade.subject());
    impact.put("newGrade", value);

    return readyMsg(
        resolved,
        "assistant.action.update_grade.summary",
        impact,
        1,
        student.value().fullName(),
        grade.subject(),
        value);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    if (!actions.tools().teachesOrAdmin(ctx, uuid(resolvedArgs, "classId"))) {
      return ToolResult.denied(msg("assistant.denied.class"));
    }
    String scoreText = text(resolvedArgs, "score");
    UpdateGradeRequest request =
        new UpdateGradeRequest(
            scoreText == null ? null : new BigDecimal(scoreText),
            text(resolvedArgs, "gradeLabel"),
            text(resolvedArgs, "notes"));
    return ToolResult.ok(grades.update(uuid(resolvedArgs, "gradeId"), ctx.userId(), request));
  }
}
