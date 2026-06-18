package com.schoolbridge.api.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.common.security.authz.EffectivePermissionService;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.identity.UserRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Guard for the dual-authorization risk (PLAN_ASSISTANT_TOOLS_REFACTOR §3): assistant tools gate on
 * the hardcoded {@link Tool#roles()} set and call services directly, bypassing the controller-level
 * {@code @RequirePermission}/{@code PermissionAspect}. Nothing otherwise keeps the two models in
 * step — if an admin re-maps {@code role_permissions}, a tool's compiled-in role set will not
 * follow.
 *
 * <p>This test pins them together: for every <em>staff</em> role a tool is offered to (TEACHER /
 * SCHOOL_ADMIN), that role MUST hold the tool's backing permission in the DB-seeded grants ({@code
 * 015-authz.sql}), read through the real {@link EffectivePermissionService}. A tool exposed to a
 * role that lacks the matching permission at the controller layer is a privilege-escalation path
 * and fails here.
 *
 * <p>PARENT is intentionally excluded from the permission check: parent tools are child-scoped and
 * gated at execution time by {@code PermissionsHelper.parentLinkedTo}/{@code parentReceived...},
 * not by the coarse grants (e.g. PARENT holds no {@code ATTENDANCE_READ} yet may read their own
 * child's attendance). The mapping below still lists every tool so a newly added tool cannot
 * silently skip the guard.
 */
@SpringBootTest
class ToolPermissionGuardTest extends AbstractIntegrationTest {

  /**
   * The permission each tool's backing endpoint enforces. Used to assert staff visibility; for
   * PARENT-only tools the value documents the analogous capability but is not asserted (ownership
   * gating applies instead).
   */
  private static final Map<String, Permission> TOOL_PERMISSIONS =
      Map.ofEntries(
          // --- reads ---
          Map.entry("list_my_children", Permission.STUDENT_READ),
          Map.entry("get_child_attendance", Permission.ATTENDANCE_READ),
          Map.entry("get_child_absence_count", Permission.ATTENDANCE_READ),
          Map.entry("get_child_homework", Permission.HOMEWORK_READ),
          Map.entry("get_child_grades", Permission.GRADE_READ),
          Map.entry("get_unacknowledged_announcements", Permission.ANNOUNCEMENT_READ),
          Map.entry("list_my_classes", Permission.CLASS_READ),
          Map.entry("get_class_attendance", Permission.ATTENDANCE_READ),
          Map.entry("get_student_attendance", Permission.ATTENDANCE_READ),
          Map.entry("get_class_enrollments", Permission.CLASS_READ),
          Map.entry("get_class_grades", Permission.GRADE_READ),
          Map.entry("get_student_grades", Permission.GRADE_READ),
          Map.entry("list_homework", Permission.HOMEWORK_READ),
          Map.entry("get_homework_recipients", Permission.HOMEWORK_READ),
          Map.entry("get_announcement_recipients", Permission.ANNOUNCEMENT_READ),
          Map.entry("list_students", Permission.STUDENT_READ),
          Map.entry("list_classes", Permission.CLASS_READ),
          Map.entry("list_subjects", Permission.SUBJECT_READ),
          Map.entry("list_parent_links", Permission.PARENT_LINK_MANAGE),
          Map.entry("list_teachers", Permission.USER_READ),
          Map.entry("list_announcements", Permission.ANNOUNCEMENT_READ),
          Map.entry("get_class_grade_summary", Permission.GRADE_READ),
          // --- actions ---
          Map.entry("respond_to_absence_alert", Permission.ATTENDANCE_RECORD),
          Map.entry("acknowledge_homework", Permission.HOMEWORK_ACK),
          Map.entry("acknowledge_announcement", Permission.ANNOUNCEMENT_READ),
          Map.entry("mark_attendance", Permission.ATTENDANCE_RECORD),
          Map.entry("mark_all_present", Permission.ATTENDANCE_RECORD),
          Map.entry("create_grade", Permission.GRADE_CREATE),
          Map.entry("update_grade", Permission.GRADE_UPDATE),
          Map.entry("delete_grade", Permission.GRADE_DELETE),
          Map.entry("create_homework", Permission.HOMEWORK_CREATE),
          Map.entry("publish_homework", Permission.HOMEWORK_PUBLISH),
          Map.entry("update_homework", Permission.HOMEWORK_UPDATE),
          Map.entry("archive_homework", Permission.HOMEWORK_DELETE),
          Map.entry("post_class_announcement", Permission.ANNOUNCEMENT_SEND),
          Map.entry("post_announcement", Permission.ANNOUNCEMENT_SEND),
          Map.entry("recall_announcement", Permission.ANNOUNCEMENT_MANAGE),
          Map.entry("add_student", Permission.STUDENT_MANAGE),
          Map.entry("update_student", Permission.STUDENT_MANAGE),
          Map.entry("delete_student", Permission.STUDENT_MANAGE),
          Map.entry("create_class", Permission.CLASS_MANAGE),
          Map.entry("update_class", Permission.CLASS_MANAGE),
          Map.entry("delete_class", Permission.CLASS_MANAGE),
          Map.entry("enroll_student", Permission.ENROLLMENT_MANAGE),
          Map.entry("remove_enrollment", Permission.ENROLLMENT_MANAGE),
          Map.entry("link_parent_to_student", Permission.PARENT_LINK_MANAGE),
          Map.entry("remove_parent_link", Permission.PARENT_LINK_MANAGE),
          Map.entry("create_subject", Permission.SUBJECT_MANAGE),
          Map.entry("update_subject", Permission.SUBJECT_MANAGE),
          Map.entry("delete_subject", Permission.SUBJECT_MANAGE),
          Map.entry("add_subject_to_class", Permission.SUBJECT_MANAGE),
          Map.entry("remove_class_subject", Permission.SUBJECT_MANAGE),
          Map.entry("assign_teacher_to_subject", Permission.SUBJECT_MANAGE),
          Map.entry("remove_teacher_assignment", Permission.SUBJECT_MANAGE));

  @Autowired List<Tool> tools;
  @Autowired EffectivePermissionService permissions;

  @Test
  void everyRegisteredToolIsMappedToABackingPermission() {
    assertThat(tools)
        .allSatisfy(
            tool ->
                assertThat(TOOL_PERMISSIONS)
                    .as("tool %s must declare a backing permission in this guard", tool.name())
                    .containsKey(tool.name()));
  }

  @Test
  void everyStaffRoleAToolIsOfferedToHoldsItsBackingPermission() {
    for (Tool tool : tools) {
      Permission required = TOOL_PERMISSIONS.get(tool.name());
      for (UserRole role : tool.roles()) {
        if (role != UserRole.TEACHER && role != UserRole.SCHOOL_ADMIN) {
          continue; // PARENT is ownership-gated, not permission-gated.
        }
        Set<String> granted = permissions.permissionsForRole(role);
        assertThat(granted)
            .as(
                "tool '%s' is offered to %s, which must hold %s",
                tool.name(), role, required.name())
            .contains(required.name());
      }
    }
  }
}
