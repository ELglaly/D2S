package com.schoolbridge.api.assistant;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.assistant.tools.Tool;
import com.schoolbridge.api.assistant.tools.ToolAuthorizer;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolRegistry;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import com.schoolbridge.api.identity.auth.principal.SchoolScopedPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The CI security oracle (plain permission model): each role must see <em>exactly</em> the tools
 * whose required permission it holds. The expected set is computed from an independent, hardcoded
 * copy of the {@code 015-authz.sql} role grants and the tools' own {@code permissions()}, then
 * asserted against {@link ToolRegistry#toolsFor} driven by the real {@link ToolAuthorizer} +
 * DB-seeded {@code role_permissions}. This pins three things at once: the seed has not drifted,
 * each tool's declared permission has not changed, and the authorizer filters on it. SUPER_ADMIN
 * holds every permission and therefore sees every tool.
 */
@SpringBootTest
class AssistantToolAuthorizationOracleTest extends AbstractIntegrationTest {

  @Autowired List<Tool> tools;
  @Autowired ToolAuthorizer authorizer;

  /** Independent copy of the seeded role→permission grants (015-authz.sql). */
  private static final Map<UserRole, Set<String>> SEED =
      Map.of(
          UserRole.PARENT,
              Set.of("GRADE_READ", "HOMEWORK_READ", "HOMEWORK_ACK", "ANNOUNCEMENT_READ"),
          UserRole.TEACHER,
              Set.of(
                  "GRADE_CREATE",
                  "GRADE_READ",
                  "GRADE_UPDATE",
                  "HOMEWORK_CREATE",
                  "HOMEWORK_PUBLISH",
                  "HOMEWORK_READ",
                  "HOMEWORK_UPDATE",
                  "HOMEWORK_DELETE",
                  "ATTENDANCE_RECORD",
                  "ATTENDANCE_READ",
                  "CLASS_READ",
                  "SUBJECT_READ",
                  "STUDENT_READ",
                  "ANNOUNCEMENT_SEND",
                  "ANNOUNCEMENT_READ"),
          UserRole.SCHOOL_ADMIN,
              Set.of(
                  "GRADE_CREATE",
                  "GRADE_READ",
                  "GRADE_UPDATE",
                  "GRADE_DELETE",
                  "HOMEWORK_CREATE",
                  "HOMEWORK_PUBLISH",
                  "HOMEWORK_READ",
                  "HOMEWORK_UPDATE",
                  "HOMEWORK_DELETE",
                  "ATTENDANCE_RECORD",
                  "ATTENDANCE_READ",
                  "CLASS_MANAGE",
                  "CLASS_READ",
                  "SUBJECT_MANAGE",
                  "SUBJECT_READ",
                  "ENROLLMENT_MANAGE",
                  "STUDENT_MANAGE",
                  "STUDENT_READ",
                  "PARENT_LINK_MANAGE",
                  "ANNOUNCEMENT_SEND",
                  "ANNOUNCEMENT_READ",
                  "ANNOUNCEMENT_MANAGE",
                  "ASSISTANT_SETTINGS_MANAGE",
                  "DOCUMENT_MANAGE",
                  "USER_READ"),
          UserRole.SUPER_ADMIN,
              Arrays.stream(Permission.values()).map(Enum::name).collect(toSet()));

  @Test
  void everyRoleSeesExactlyTheToolsItHoldsAPermissionFor() {
    ToolRegistry registry = new ToolRegistry(tools, authorizer, true);
    for (UserRole role : List.of(UserRole.PARENT, UserRole.TEACHER, UserRole.SCHOOL_ADMIN)) {
      Set<String> visible =
          registry.toolsFor(ctx(role)).stream().map(Tool::name).collect(Collectors.toSet());
      assertThat(visible)
          .as("role %s must see exactly the tools whose permission it holds", role)
          .isEqualTo(expectedFor(role));
    }
  }

  @Test
  void superAdminSeesEveryTool() {
    ToolRegistry registry = new ToolRegistry(tools, authorizer, true);
    Set<String> visible =
        registry.toolsFor(ctx(UserRole.SUPER_ADMIN)).stream()
            .map(Tool::name)
            .collect(Collectors.toSet());
    assertThat(visible).isEqualTo(tools.stream().map(Tool::name).collect(Collectors.toSet()));
  }

  private Set<String> expectedFor(UserRole role) {
    Set<String> grants = SEED.get(role);
    return tools.stream()
        .filter(t -> t.permissions().stream().map(Enum::name).anyMatch(grants::contains))
        .map(Tool::name)
        .collect(Collectors.toSet());
  }

  private static ToolContext ctx(UserRole role) {
    UUID schoolId = UUID.randomUUID();
    SchoolScopedPrincipal principal =
        role == UserRole.PARENT
            ? new ParentPrincipal(UUID.randomUUID(), schoolId)
            : new StaffPrincipal(UUID.randomUUID(), schoolId, role);
    return new ToolContext(schoolId, principal, role, Locale.ENGLISH, null);
  }
}
