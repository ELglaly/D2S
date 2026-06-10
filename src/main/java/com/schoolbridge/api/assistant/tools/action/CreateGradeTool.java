package com.schoolbridge.api.assistant.tools.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.grades.GradeService;
import com.schoolbridge.api.grades.dto.CreateGradeRequest;
import com.schoolbridge.api.identity.UserRole;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** TEACHER/ADMIN — record a grade for a student. Mirrors {@code POST /grades}. */
@Component
public class CreateGradeTool extends AbstractActionTool {

  private final GradeService grades;

  public CreateGradeTool(ActionSupport actions, GradeService grades) {
    super(actions);
    this.grades = grades;
  }

  @Override
  public String name() {
    return "create_grade";
  }

  @Override
  public String description() {
    return "Record a grade (score or letter) for a student in a class subject.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Class name", true)
        .str("studentRef", "Student's full name", true)
        .str("subject", "Subject/assessment name", true)
        .str("period", "Term/period label; defaults to \"Term\"", false)
        .number("score", "Numeric score", false)
        .str("gradeLabel", "Letter grade (e.g. A, B+)", false)
        .str("notes", "Optional notes", false)
        .build();
  }

  @Override
  public Set<UserRole> roles() {
    return Set.of(UserRole.TEACHER, UserRole.SCHOOL_ADMIN);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<SchoolClassResponse> clazz = actions.tools().clazz(ctx, Args.str(args, "classRef"));
    if (clazz.clarified()) {
      return clarify(clazz);
    }
    UUID classId = clazz.value().id();
    if (!actions.tools().teachesOrAdmin(ctx, classId)) {
      return deniedKey("assistant.denied.class");
    }
    Resolved<StudentResponse> student = actions.tools().student(ctx, Args.str(args, "studentRef"));
    if (student.clarified()) {
      return clarify(student);
    }
    String subject = Args.str(args, "subject");
    if (subject == null) {
      return reject(ToolResult.clarify(msg("assistant.grade.subject_required")));
    }
    Double score = Args.number(args, "score");
    String gradeLabel = Args.str(args, "gradeLabel");
    if (score == null && gradeLabel == null) {
      return reject(ToolResult.clarify(msg("assistant.grade.score_required")));
    }
    String period = Args.str(args, "period");
    if (period == null) {
      period = "Term";
    }
    String notes = Args.str(args, "notes");

    ObjectNode resolved = newArgs();
    resolved.put("classId", classId.toString());
    resolved.put("studentId", student.value().id().toString());
    resolved.put("subject", subject);
    resolved.put("period", period);
    if (score != null) {
      resolved.put("score", score);
    }
    if (gradeLabel != null) {
      resolved.put("gradeLabel", gradeLabel);
    }
    if (notes != null) {
      resolved.put("notes", notes);
    }

    String value = score != null ? String.valueOf(score) : gradeLabel;
    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("student", student.value().fullName());
    impact.put("class", clazz.value().name());
    impact.put("subject", subject);
    impact.put("period", period);
    impact.put("grade", value);

    String en =
        "I'll record "
            + subject
            + " = "
            + value
            + " ("
            + period
            + ") for "
            + student.value().fullName()
            + ". Confirm?";
    String ar =
        "سأسجّل "
            + subject
            + " = "
            + value
            + " ("
            + period
            + ") لـ "
            + student.value().fullName()
            + ". أؤكّد؟";
    return ready(resolved, ar, en, impact, 1);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    UUID classId = uuid(resolvedArgs, "classId");
    if (!actions.tools().teachesOrAdmin(ctx, classId)) {
      return ToolResult.denied(msg("assistant.denied.class"));
    }
    String scoreText = text(resolvedArgs, "score");
    CreateGradeRequest request =
        new CreateGradeRequest(
            uuid(resolvedArgs, "studentId"),
            classId,
            text(resolvedArgs, "subject"),
            text(resolvedArgs, "period"),
            scoreText == null ? null : new BigDecimal(scoreText),
            text(resolvedArgs, "gradeLabel"),
            text(resolvedArgs, "notes"));
    return ToolResult.ok(grades.create(ctx.schoolId(), ctx.userId(), request));
  }
}
