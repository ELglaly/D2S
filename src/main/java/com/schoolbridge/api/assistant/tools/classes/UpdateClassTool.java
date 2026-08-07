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
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.dto.UpdateSchoolClassRequest;
import com.schoolbridge.api.classes.service.SchoolClassService;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.identity.dto.UserResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** ADMIN — update a class. Mirrors {@code PATCH /classes/{id}}. */
@Component
public class UpdateClassTool extends AbstractActionTool {

  private final SchoolClassService classes;

  public UpdateClassTool(ActionSupport actions, SchoolClassService classes) {
    super(actions);
    this.classes = classes;
  }

  @Override
  public String name() {
    return "update_class";
  }

  @Override
  public String description() {
    return "Update a class's name, grade level, academic year, or homeroom teacher.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Current class name", true)
        .str("name", "New class name", false)
        .str("gradeLevel", "New grade level", false)
        .str("academicYear", "New academic year", false)
        .str("homeroomTeacherRef", "New homeroom teacher name", false)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.CLASS_MANAGE);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<SchoolClassResponse> clazz = actions.tools().clazz(ctx, Args.str(args, "classRef"));
    if (clazz.clarified()) {
      return clarify(clazz);
    }
    SchoolClassResponse existing = clazz.value();
    String name = Args.str(args, "name");
    name = name != null ? name : existing.name();
    String gradeLevel = Args.str(args, "gradeLevel");
    gradeLevel = gradeLevel != null ? gradeLevel : existing.gradeLevel();
    String academicYear = Args.str(args, "academicYear");
    academicYear = academicYear != null ? academicYear : existing.academicYear();

    ObjectNode resolved = newArgs();
    resolved.put("classId", existing.id().toString());
    resolved.put("name", name);
    resolved.put("gradeLevel", gradeLevel);
    resolved.put("academicYear", academicYear);

    String teacherRef = Args.str(args, "homeroomTeacherRef");
    if (teacherRef != null) {
      Resolved<UserResponse> teacher = actions.tools().teacher(ctx, teacherRef);
      if (teacher.clarified()) {
        return clarify(teacher);
      }
      resolved.put("homeroomTeacherId", teacher.value().id().toString());
    } else if (existing.homeroomTeacherId() != null) {
      resolved.put("homeroomTeacherId", existing.homeroomTeacherId().toString());
    }

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("class", existing.name());
    impact.put("newName", name);

    return readyMsg(
        resolved, "assistant.action.update_class.summary", impact, 1, existing.name(), name);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    String teacherId = text(resolvedArgs, "homeroomTeacherId");
    UpdateSchoolClassRequest request =
        new UpdateSchoolClassRequest(
            text(resolvedArgs, "name"),
            text(resolvedArgs, "gradeLevel"),
            text(resolvedArgs, "academicYear"),
            teacherId == null ? null : UUID.fromString(teacherId));
    return ToolResult.ok(classes.update(uuid(resolvedArgs, "classId"), request));
  }
}
