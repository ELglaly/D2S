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
import com.schoolbridge.api.classes.dto.ParentStudentLinkResponse;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.classes.service.ParentStudentLinkService;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.dto.UserResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** ADMIN — remove a parent-student link. Mirrors {@code DELETE /parent-links/{id}}. */
@Component
public class RemoveParentLinkTool extends AbstractActionTool {

  private final ParentStudentLinkService links;

  public RemoveParentLinkTool(ActionSupport actions, ParentStudentLinkService links) {
    super(actions);
    this.links = links;
  }

  @Override
  public String name() {
    return "remove_parent_link";
  }

  @Override
  public String description() {
    return "Remove the link between a parent and a student.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("parentRef", "Parent's name", true)
        .str("studentRef", "Student's full name", true)
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
    UUID parentUserId = parent.value().id();
    Optional<ParentStudentLinkResponse> link =
        links.listByStudent(student.value().id()).stream()
            .filter(l -> l.parentUserId().equals(parentUserId))
            .findFirst();
    if (link.isEmpty()) {
      return reject(ToolResult.clarify(msg("assistant.parent_link.not_found")));
    }
    ObjectNode resolved = newArgs();
    resolved.put("linkId", link.get().id().toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("parent", parent.value().name());
    impact.put("student", student.value().fullName());

    return readyMsg(
        resolved,
        "assistant.action.remove_parent_link.summary",
        impact,
        1,
        parent.value().name(),
        student.value().fullName());
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    links.delete(uuid(resolvedArgs, "linkId"));
    return ToolResult.ok(Map.of("removed", true));
  }
}
