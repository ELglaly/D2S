package com.schoolbridge.api.assistant.tools.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.StudentStatus;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.classes.dto.UpdateStudentRequest;
import com.schoolbridge.api.classes.service.StudentService;
import com.schoolbridge.api.identity.UserRole;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** ADMIN — update a student record. Mirrors {@code PATCH /students/{id}}. */
@Component
public class UpdateStudentTool extends AbstractActionTool {

  private final StudentService students;

  public UpdateStudentTool(ActionSupport actions, StudentService students) {
    super(actions);
    this.students = students;
  }

  @Override
  public String name() {
    return "update_student";
  }

  @Override
  public String description() {
    return "Update a student's name, date of birth, or external ID.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("studentRef", "Student's current full name", true)
        .str("fullName", "New full name", false)
        .date("dateOfBirth", "New date of birth YYYY-MM-DD", false)
        .str("externalId", "New external ID", false)
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
    StudentResponse existing = student.value();
    String fullName = Args.str(args, "fullName");
    fullName = fullName != null ? fullName : existing.fullName();
    LocalDate dob = Args.date(args, "dateOfBirth");
    dob = dob != null ? dob : existing.dateOfBirth();
    String externalId = Args.str(args, "externalId");
    externalId = externalId != null ? externalId : existing.externalId();

    ObjectNode resolved = newArgs();
    resolved.put("studentId", existing.id().toString());
    resolved.put("fullName", fullName);
    if (dob != null) {
      resolved.put("dateOfBirth", dob.toString());
    }
    if (externalId != null) {
      resolved.put("externalId", externalId);
    }
    resolved.put("status", existing.status().name());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("student", existing.fullName());
    impact.put("newName", fullName);

    String en =
        "I'll update student \""
            + existing.fullName()
            + "\" (name → \""
            + fullName
            + "\"). Confirm?";
    String ar = "سأعدّل الطالب «" + existing.fullName() + "» (الاسم ← «" + fullName + "»). أؤكّد؟";
    return ready(resolved, ar, en, impact, 1);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    UpdateStudentRequest request =
        new UpdateStudentRequest(
            text(resolvedArgs, "fullName"),
            localDate(resolvedArgs, "dateOfBirth"),
            text(resolvedArgs, "externalId"),
            StudentStatus.valueOf(text(resolvedArgs, "status")));
    return ToolResult.ok(students.update(uuid(resolvedArgs, "studentId"), request));
  }
}
