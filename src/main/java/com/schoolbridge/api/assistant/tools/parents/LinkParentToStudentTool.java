package com.schoolbridge.api.assistant.tools.parents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.AbstractActionTool;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.RelationshipType;
import com.schoolbridge.api.classes.dto.CreateParentLinkRequest;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.classes.service.ParentStudentLinkService;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.dto.UserResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** ADMIN — link a parent to a student. Mirrors {@code POST /parent-links}. */
@Component
public class LinkParentToStudentTool extends AbstractActionTool {

  private final ParentStudentLinkService links;

  public LinkParentToStudentTool(ActionSupport actions, ParentStudentLinkService links) {
    super(actions);
    this.links = links;
  }

  @Override
  public String name() {
    return "link_parent_to_student";
  }

  @Override
  public String description() {
    return "Link a parent account to a student with a relationship.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("parentRef", "Parent's name", true)
        .str("studentRef", "Student's full name", true)
        .enumStr("relationship", "Relationship", true, List.of("MOTHER", "FATHER", "GUARDIAN"))
        .bool("primaryContact", "Whether this is the primary contact", false)
        .build();
  }

  @Override
  public Set<UserRole> roles() {
    return Set.of(UserRole.SCHOOL_ADMIN);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<UserResponse> parent = actions.tools().parent(ctx, Args.str(args, "parentRef"));
    if (parent.clarified()) {
      return clarify(parent);
    }
    Resolved<StudentResponse> student = actions.tools().student(ctx, Args.str(args, "studentRef"));
    if (student.clarified()) {
      return clarify(student);
    }
    RelationshipType relationship = parseRelationship(Args.str(args, "relationship"));
    if (relationship == null) {
      return reject(ToolResult.clarify(msg("assistant.link.relationship_required")));
    }
    boolean primary = Args.bool(args, "primaryContact", false);

    ObjectNode resolved = newArgs();
    resolved.put("parentUserId", parent.value().id().toString());
    resolved.put("studentId", student.value().id().toString());
    resolved.put("relationship", relationship.name());
    resolved.put("primaryContact", primary);

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("parent", parent.value().name());
    impact.put("student", student.value().fullName());
    impact.put("relationship", relationship.name());

    return readyMsg(
        resolved,
        "assistant.action.link_parent_to_student.summary",
        impact,
        1,
        parent.value().name(),
        student.value().fullName(),
        relationship.name());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    CreateParentLinkRequest request =
        new CreateParentLinkRequest(
            uuid(resolvedArgs, "parentUserId"),
            uuid(resolvedArgs, "studentId"),
            RelationshipType.valueOf(text(resolvedArgs, "relationship")),
            resolvedArgs.get("primaryContact").asBoolean());
    return ToolResult.ok(links.create(ctx.schoolId(), request));
  }

  private static RelationshipType parseRelationship(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return RelationshipType.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
