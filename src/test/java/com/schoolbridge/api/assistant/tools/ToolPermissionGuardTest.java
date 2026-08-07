package com.schoolbridge.api.assistant.tools;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.common.security.authz.EffectivePermissionService;
import com.schoolbridge.api.identity.UserRole;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Catalog-completeness guard for the permission binding (PLAN_ASSISTANT_PERMISSION_AUTHZ). Every
 * registered tool must declare a backing {@code permissions()} set, and every permission it
 * declares must be a real seeded permission ({@code 015-authz.sql}) — verified against SUPER_ADMIN,
 * who holds every grant. This turns a forgotten or misspelled binding (which would silently make a
 * tool invisible to everyone) into a red build. Exact per-role visibility is pinned separately by
 * {@code AssistantToolAuthorizationOracleTest}.
 */
@SpringBootTest
class ToolPermissionGuardTest extends AbstractIntegrationTest {

  @Autowired List<Tool> tools;
  @Autowired EffectivePermissionService permissions;

  @Test
  void everyToolDeclaresAtLeastOnePermission() {
    assertThat(tools)
        .allSatisfy(
            tool ->
                assertThat(tool.permissions())
                    .as("tool %s must declare a backing permission", tool.name())
                    .isNotEmpty());
  }

  @Test
  void everyToolPermissionIsSeeded() {
    Set<String> seeded = permissions.permissionsForRole(UserRole.SUPER_ADMIN);
    assertThat(seeded).as("SUPER_ADMIN must hold every seeded permission").isNotEmpty();
    for (Tool tool : tools) {
      Set<String> declared = tool.permissions().stream().map(Enum::name).collect(toSet());
      assertThat(seeded)
          .as("tool '%s' declares a permission that is not seeded", tool.name())
          .containsAll(declared);
    }
  }
}
