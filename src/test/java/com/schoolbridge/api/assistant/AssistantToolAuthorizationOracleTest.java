package com.schoolbridge.api.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.assistant.tools.Tool;
import com.schoolbridge.api.assistant.tools.ToolRegistry;
import com.schoolbridge.api.identity.UserRole;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The CI security oracle (plan §18): each assistant tool must be registered for <em>exactly</em>
 * the roles whose backing endpoint guard it would pass — no more, no less. This locks tool exposure
 * to the existing REST authorization so the assistant can never widen access. No tool may target
 * SUPER_ADMIN (deferred).
 */
@SpringBootTest
class AssistantToolAuthorizationOracleTest extends AbstractIntegrationTest {

  @Autowired ToolRegistry registry;

  private static final UserRole P = UserRole.PARENT;
  private static final UserRole T = UserRole.TEACHER;
  private static final UserRole A = UserRole.SCHOOL_ADMIN;

  private static final Map<String, Set<UserRole>> EXPECTED = expected();

  @Test
  void everyToolIsRegisteredForExactlyItsAuthorizedRoles() {
    Map<String, Set<UserRole>> actual =
        registry.all().stream().collect(Collectors.toMap(Tool::name, Tool::roles));

    assertThat(actual)
        .as("the set of registered assistant tools must match the authorization oracle")
        .containsOnlyKeys(EXPECTED.keySet().toArray(String[]::new));

    EXPECTED.forEach(
        (name, roles) ->
            assertThat(actual.get(name))
                .as("tool '%s' must be registered for exactly %s", name, roles)
                .isEqualTo(roles));
  }

  @Test
  void noToolTargetsSuperAdmin() {
    assertThat(registry.all())
        .filteredOn(t -> t.roles().contains(UserRole.SUPER_ADMIN))
        .as("SUPER_ADMIN is deferred — no tool may target it")
        .isEmpty();
  }

  private static Map<String, Set<UserRole>> expected() {
    Map<String, Set<UserRole>> m = new LinkedHashMap<>();
    // Parent reads
    m.put("list_my_children", Set.of(P));
    m.put("get_child_attendance", Set.of(P));
    m.put("get_child_absence_count", Set.of(P));
    m.put("get_child_homework", Set.of(P));
    m.put("get_child_grades", Set.of(P));
    m.put("get_unacknowledged_announcements", Set.of(P));
    // Teacher / admin reads
    m.put("list_my_classes", Set.of(T));
    m.put("get_class_attendance", Set.of(T, A));
    m.put("get_student_attendance", Set.of(T, A));
    m.put("get_class_enrollments", Set.of(T, A));
    m.put("get_class_grades", Set.of(T, A));
    m.put("get_student_grades", Set.of(T, A));
    m.put("list_homework", Set.of(T, A));
    m.put("get_homework_recipients", Set.of(T, A));
    m.put("get_class_grade_summary", Set.of(T, A));
    // Admin reads
    m.put("get_announcement_recipients", Set.of(A));
    m.put("list_students", Set.of(A));
    m.put("list_classes", Set.of(A));
    m.put("list_subjects", Set.of(A));
    m.put("list_parent_links", Set.of(A));
    m.put("list_teachers", Set.of(A));
    m.put("list_announcements", Set.of(A));
    // Parent actions
    m.put("respond_to_absence_alert", Set.of(P));
    m.put("acknowledge_homework", Set.of(P));
    m.put("acknowledge_announcement", Set.of(P));
    // Teacher / admin actions
    m.put("mark_attendance", Set.of(T, A));
    m.put("mark_all_present", Set.of(T, A));
    m.put("create_grade", Set.of(T, A));
    m.put("update_grade", Set.of(T, A));
    m.put("create_homework", Set.of(T, A));
    m.put("publish_homework", Set.of(T, A));
    m.put("update_homework", Set.of(T, A));
    m.put("archive_homework", Set.of(T, A));
    m.put("post_class_announcement", Set.of(T, A));
    // Admin actions
    m.put("add_student", Set.of(A));
    m.put("update_student", Set.of(A));
    m.put("delete_student", Set.of(A));
    m.put("create_class", Set.of(A));
    m.put("update_class", Set.of(A));
    m.put("delete_class", Set.of(A));
    m.put("enroll_student", Set.of(A));
    m.put("remove_enrollment", Set.of(A));
    m.put("link_parent_to_student", Set.of(A));
    m.put("remove_parent_link", Set.of(A));
    m.put("create_subject", Set.of(A));
    m.put("update_subject", Set.of(A));
    m.put("delete_subject", Set.of(A));
    m.put("add_subject_to_class", Set.of(A));
    m.put("remove_class_subject", Set.of(A));
    m.put("assign_teacher_to_subject", Set.of(A));
    m.put("remove_teacher_assignment", Set.of(A));
    m.put("post_announcement", Set.of(A));
    m.put("recall_announcement", Set.of(A));
    m.put("delete_grade", Set.of(A));
    return m;
  }
}
