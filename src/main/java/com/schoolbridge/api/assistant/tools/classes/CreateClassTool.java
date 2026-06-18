package com.schoolbridge.api.assistant.tools.classes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.AbstractActionTool;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.dto.CreateSchoolClassRequest;
import com.schoolbridge.api.classes.service.SchoolClassService;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.dto.UserResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** ADMIN — create a class. Mirrors {@code POST /classes}. */
@Component
public class CreateClassTool extends AbstractActionTool {

  private final SchoolClassService classes;

  public CreateClassTool(ActionSupport actions, SchoolClassService classes) {
    super(actions);
    this.classes = classes;
  }

  @Override
  public String name() {
    return "create_class";
  }

  @Override
  public String description() {
    return "Create a new class with a grade level and academic year.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("name", "Class name (e.g. 5A)", true)
        .str("gradeLevel", "Grade level (e.g. Grade 5)", true)
        .str("academicYear", "Academic year (e.g. 2025-2026)", true)
        .str("homeroomTeacherRef", "Optional homeroom teacher name", false)
        .build();
  }

  @Override
  public Set<UserRole> roles() {
    return Set.of(UserRole.SCHOOL_ADMIN);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    String name = Args.str(args, "name");
    String gradeLevel = Args.str(args, "gradeLevel");
    String academicYear = Args.str(args, "academicYear");
    if (name == null || gradeLevel == null || academicYear == null) {
      return reject(ToolResult.clarify(msg("assistant.class.fields_required")));
    }
    ObjectNode resolved = newArgs();
    resolved.put("name", name);
    resolved.put("gradeLevel", gradeLevel);
    resolved.put("academicYear", academicYear);
    String teacherRef = Args.str(args, "homeroomTeacherRef");
    String teacherName = null;
    if (teacherRef != null) {
      Resolved<UserResponse> teacher = actions.tools().teacher(ctx, teacherRef);
      if (teacher.clarified()) {
        return clarify(teacher);
      }
      resolved.put("homeroomTeacherId", teacher.value().id().toString());
      teacherName = teacher.value().name();
    }

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("name", name);
    impact.put("gradeLevel", gradeLevel);
    impact.put("academicYear", academicYear);
    if (teacherName != null) {
      impact.put("homeroomTeacher", teacherName);
    }

    String teacherClauseEn =
        teacherName != null
            ? " " + msgIn(EN, "assistant.action.create_class.with_teacher", teacherName)
            : "";
    String teacherClauseAr =
        teacherName != null
            ? " " + msgIn(AR, "assistant.action.create_class.with_teacher", teacherName)
            : "";
    String en =
        msgIn(
            EN,
            "assistant.action.create_class.summary",
            name,
            gradeLevel,
            academicYear,
            teacherClauseEn);
    String ar =
        msgIn(
            AR,
            "assistant.action.create_class.summary",
            name,
            gradeLevel,
            academicYear,
            teacherClauseAr);
    return ready(resolved, ar, en, impact, 1);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    String teacherId = text(resolvedArgs, "homeroomTeacherId");
    CreateSchoolClassRequest request =
        new CreateSchoolClassRequest(
            text(resolvedArgs, "name"),
            text(resolvedArgs, "gradeLevel"),
            text(resolvedArgs, "academicYear"),
            teacherId == null ? null : java.util.UUID.fromString(teacherId));
    return ToolResult.ok(classes.create(ctx.schoolId(), request));
  }
}
