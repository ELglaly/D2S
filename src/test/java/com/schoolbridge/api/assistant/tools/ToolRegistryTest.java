package com.schoolbridge.api.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.common.security.authz.EffectivePermissionService;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

  private static final Tool PARENT_READ =
      tool("list_my_children", ToolKind.READ, Permission.STUDENT_READ);
  private static final Tool TEACHER_READ =
      tool("get_class_grades", ToolKind.READ, Permission.GRADE_READ);
  private static final Tool TEACHER_ACTION =
      tool("mark_attendance", ToolKind.ACTION, Permission.ATTENDANCE_RECORD);
  private static final Tool ADMIN_ACTION =
      tool("add_student", ToolKind.ACTION, Permission.STUDENT_MANAGE);

  private final List<Tool> all = List.of(PARENT_READ, TEACHER_READ, TEACHER_ACTION, ADMIN_ACTION);

  @Test
  void callerSeesOnlyToolsWhosePermissionTheyHold() {
    ToolRegistry registry = new ToolRegistry(all, authorizer());
    List<Tool> tools = registry.toolsFor(ctx(UserRole.PARENT, parent()));
    assertThat(tools).containsExactly(PARENT_READ);
  }

  @Test
  void teacherSeesOnlyReadToolsBackedByTheirGrants() {
    ToolRegistry registry = new ToolRegistry(all, authorizer());
    List<Tool> tools = registry.toolsFor(ctx(UserRole.TEACHER, staff(UserRole.TEACHER)));
    assertThat(tools).containsExactly(TEACHER_READ);
  }

  @Test
  void actionToolsAreNeverRegisteredInV1() {
    ToolRegistry registry = new ToolRegistry(all, authorizer());
    List<Tool> tools = registry.toolsFor(ctx(UserRole.SCHOOL_ADMIN, staff(UserRole.SCHOOL_ADMIN)));
    assertThat(tools).containsExactly(TEACHER_READ);
    assertThat(registry.all()).containsOnly(PARENT_READ, TEACHER_READ);
  }

  @Test
  void findNeverExposesAnActionTool() {
    ToolRegistry registry = new ToolRegistry(all, authorizer());
    assertThat(
            registry.find("add_student", ctx(UserRole.SCHOOL_ADMIN, staff(UserRole.SCHOOL_ADMIN))))
        .isEmpty();
  }

  private static ToolAuthorizer authorizer() {
    EffectivePermissionService perms = mock(EffectivePermissionService.class);
    when(perms.permissionsForRole(UserRole.PARENT)).thenReturn(Set.of("STUDENT_READ"));
    when(perms.permissionsForRole(UserRole.TEACHER))
        .thenReturn(Set.of("GRADE_READ", "ATTENDANCE_RECORD"));
    when(perms.permissionsForRole(UserRole.SCHOOL_ADMIN))
        .thenReturn(Set.of("GRADE_READ", "ATTENDANCE_RECORD", "STUDENT_MANAGE"));
    return new ToolAuthorizer(perms);
  }

  private static ToolContext ctx(UserRole role, Object principal) {
    return new ToolContext(
        UUID.randomUUID(),
        (com.schoolbridge.api.identity.auth.principal.SchoolScopedPrincipal) principal,
        role,
        Locale.ENGLISH,
        null);
  }

  private static StaffPrincipal staff(UserRole role) {
    return new StaffPrincipal(UUID.randomUUID(), UUID.randomUUID(), role);
  }

  private static ParentPrincipal parent() {
    return new ParentPrincipal(UUID.randomUUID(), UUID.randomUUID());
  }

  private static Tool tool(String name, ToolKind kind, Permission permission) {
    return new Tool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return name;
      }

      @Override
      public JsonNode inputSchema() {
        return Schema.empty();
      }

      @Override
      public ToolKind kind() {
        return kind;
      }

      @Override
      public Set<Permission> permissions() {
        return Set.of(permission);
      }
    };
  }
}
