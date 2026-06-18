package com.schoolbridge.api.assistant.tools.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.attendance.GetClassAttendanceTool;
import com.schoolbridge.api.assistant.tools.homework.ListHomeworkTool;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.attendance.AttendanceService;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.homework.HomeworkService;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeacherReadToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Mock ToolSupport support;
  @Mock AttendanceService attendance;
  @Mock HomeworkService homework;

  private final UUID classId = UUID.randomUUID();

  @Test
  void classAttendanceHappyForAdmin() {
    ToolContext ctx = ctx(UserRole.SCHOOL_ADMIN);
    when(support.clazz(ctx, "5A")).thenReturn(new Resolved.Of<>(clazz()));
    when(support.teachesOrAdmin(ctx, classId)).thenReturn(true);
    when(attendance.roster(eq(classId), any())).thenReturn(java.util.List.of());

    ToolResult r =
        new GetClassAttendanceTool(support, attendance).execute(arg("classRef", "5A"), ctx);

    assertThat(r.isOk()).isTrue();
    verify(attendance).roster(eq(classId), any());
  }

  @Test
  void classAttendanceDeniedForTeacherOutOfScope() {
    ToolContext ctx = ctx(UserRole.TEACHER);
    when(support.clazz(ctx, "5A")).thenReturn(new Resolved.Of<>(clazz()));
    when(support.teachesOrAdmin(ctx, classId)).thenReturn(false);
    when(support.denied("assistant.denied.class")).thenReturn(ToolResult.denied("no"));

    ToolResult r =
        new GetClassAttendanceTool(support, attendance).execute(arg("classRef", "5A"), ctx);

    assertThat(r.status()).isEqualTo(ToolResult.Status.DENIED);
    verify(attendance, never()).roster(any(), any());
  }

  @Test
  void classAttendanceClarifiesOnUnknownClass() {
    ToolContext ctx = ctx(UserRole.TEACHER);
    when(support.clazz(ctx, "Nope"))
        .thenReturn(new Resolved.NeedsClarification<>(ToolResult.clarify("which class?")));

    ToolResult r =
        new GetClassAttendanceTool(support, attendance).execute(arg("classRef", "Nope"), ctx);

    assertThat(r.status()).isEqualTo(ToolResult.Status.CLARIFY);
    verify(attendance, never()).roster(any(), any());
  }

  @Test
  void listHomeworkRequiresClassForTeacher() {
    ToolContext ctx = ctx(UserRole.TEACHER);
    when(support.clarify("assistant.homework.class_required"))
        .thenReturn(ToolResult.clarify("class?"));

    ToolResult r = new ListHomeworkTool(support, homework).execute(JSON.createObjectNode(), ctx);

    assertThat(r.status()).isEqualTo(ToolResult.Status.CLARIFY);
    verify(homework, never()).list(any(), any(), any(), any(), any());
  }

  private SchoolClassResponse clazz() {
    return new SchoolClassResponse(
        classId, UUID.randomUUID(), "5A", "Grade 5", "2025-2026", null, null, null);
  }

  private static ToolContext ctx(UserRole role) {
    return new ToolContext(
        UUID.randomUUID(),
        new StaffPrincipal(UUID.randomUUID(), UUID.randomUUID(), role),
        role,
        Locale.ENGLISH,
        null);
  }

  private static JsonNode arg(String k, String v) {
    return JSON.createObjectNode().put(k, v);
  }
}
