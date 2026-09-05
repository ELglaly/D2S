package com.schoolbridge.api.assistant.tools.staff;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.UserService;
import com.schoolbridge.api.identity.dto.UserResponse;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** ADMIN â€” list the school's teachers. Mirrors a role-filtered {@code GET /users}. */
@Component
public class ListTeachersTool implements ReadTool {

  private static final int MAX = 200;

  private final UserService users;

  public ListTeachersTool(UserService users) {
    this.users = users;
  }

  @Override
  public String name() {
    return "list_teachers";
  }

  @Override
  public String description() {
    return "List the teachers in the school.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder().build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.USER_READ);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    List<UserResponse> teachers =
        users.list(ctx.schoolId(), PageRequest.of(0, MAX)).getContent().stream()
            .filter(u -> u.role() == UserRole.TEACHER)
            .toList();
    return ToolResult.ok(teachers);
  }
}
