package com.schoolbridge.api.assistant.tools.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.attendance.GetChildAttendanceTool;
import com.schoolbridge.api.assistant.tools.grades.GetChildGradesTool;
import com.schoolbridge.api.assistant.tools.student.ListMyChildrenTool;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.attendance.AttendanceService;
import com.schoolbridge.api.classes.dto.ParentChildResponse;
import com.schoolbridge.api.classes.service.ParentChildrenService;
import com.schoolbridge.api.common.security.PermissionsHelper;
import com.schoolbridge.api.grades.GradeService;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ParentReadToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Mock ToolSupport support;
  @Mock PermissionsHelper perms;
  @Mock AttendanceService attendance;
  @Mock GradeService grades;
  @Mock ParentChildrenService parentChildren;

  private final UUID studentId = UUID.randomUUID();
  private final ToolContext ctx =
      new ToolContext(
          UUID.randomUUID(),
          new ParentPrincipal(UUID.randomUUID(), UUID.randomUUID()),
          UserRole.PARENT,
          Locale.ENGLISH,
          null);

  @Test
  void listMyChildrenReturnsChildren() {
    ParentChildResponse child = child();
    when(parentChildren.listChildren(ctx.userId())).thenReturn(List.of(child));

    ToolResult r = new ListMyChildrenTool(parentChildren).execute(empty(), ctx);

    assertThat(r.isOk()).isTrue();
    assertThat(r.data()).isEqualTo(List.of(child));
  }

  @Test
  void childAttendanceHappyPath() {
    when(support.child(ctx, "Ahmed")).thenReturn(new Resolved.Of<>(child()));
    when(perms.parentLinkedTo(studentId)).thenReturn(true);
    when(attendance.history(eq(studentId), any(), any())).thenReturn(List.of());

    ToolResult r =
        new GetChildAttendanceTool(support, perms, attendance)
            .execute(args("childName", "Ahmed"), ctx);

    assertThat(r.isOk()).isTrue();
    verify(attendance).history(eq(studentId), any(), any());
  }

  @Test
  void childAttendanceDeniedWhenNotLinked() {
    when(support.child(ctx, "Ahmed")).thenReturn(new Resolved.Of<>(child()));
    when(perms.parentLinkedTo(studentId)).thenReturn(false);
    when(support.denied("assistant.denied.child")).thenReturn(ToolResult.denied("no"));

    ToolResult r =
        new GetChildAttendanceTool(support, perms, attendance)
            .execute(args("childName", "Ahmed"), ctx);

    assertThat(r.status()).isEqualTo(ToolResult.Status.DENIED);
    verify(attendance, never()).history(any(), any(), any());
  }

  @Test
  void childAttendancePropagatesClarification() {
    when(support.child(ctx, null))
        .thenReturn(new Resolved.NeedsClarification<>(ToolResult.clarify("which?")));

    ToolResult r = new GetChildAttendanceTool(support, perms, attendance).execute(empty(), ctx);

    assertThat(r.status()).isEqualTo(ToolResult.Status.CLARIFY);
    verify(attendance, never()).history(any(), any(), any());
  }

  @Test
  void childGradesFiltersBySubject() {
    when(support.child(ctx, "Ahmed")).thenReturn(new Resolved.Of<>(child()));
    when(perms.parentLinkedTo(studentId)).thenReturn(true);
    when(grades.listByStudent(studentId))
        .thenReturn(List.of(grade("Math"), grade("Science"), grade("Mathematics")));

    ObjectNode args = (ObjectNode) args("childName", "Ahmed");
    args.put("subject", "math");
    ToolResult r = new GetChildGradesTool(support, perms, grades).execute(args, ctx);

    assertThat(r.isOk()).isTrue();
    @SuppressWarnings("unchecked")
    List<?> data = (List<?>) r.data();
    assertThat(data).hasSize(2); // Math + Mathematics
  }

  private ParentChildResponse child() {
    return new ParentChildResponse(studentId, "Ahmed", null, null, null, false, List.of());
  }

  private static com.schoolbridge.api.grades.dto.GradeRecordResponse grade(String subject) {
    return new com.schoolbridge.api.grades.dto.GradeRecordResponse(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        subject,
        "T1",
        null,
        "A",
        UUID.randomUUID(),
        null,
        null,
        null,
        null);
  }

  private static com.fasterxml.jackson.databind.JsonNode empty() {
    return JSON.createObjectNode();
  }

  private static com.fasterxml.jackson.databind.JsonNode args(String k, String v) {
    return JSON.createObjectNode().put(k, v);
  }
}
