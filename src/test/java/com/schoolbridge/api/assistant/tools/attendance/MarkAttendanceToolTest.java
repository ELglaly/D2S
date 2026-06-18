package com.schoolbridge.api.assistant.tools.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.confirm.ConfirmationTokenService;
import com.schoolbridge.api.assistant.confirm.PendingActionStore;
import com.schoolbridge.api.assistant.llm.AssistantProperties;
import com.schoolbridge.api.assistant.tools.PreviewOutcome;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.attendance.AttendanceService;
import com.schoolbridge.api.attendance.dto.MarkAttendanceRequest;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.common.i18n.MessageResolver;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarkAttendanceToolTest {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @Mock PendingActionStore store;
  @Mock ConfirmationTokenService tokens;
  @Mock MessageResolver messages;
  @Mock ToolSupport toolSupport;
  @Mock AttendanceService attendance;

  private final UUID classId = UUID.randomUUID();
  private final UUID studentId = UUID.randomUUID();
  private MarkAttendanceTool tool;

  private final ToolContext ctx =
      new ToolContext(
          UUID.randomUUID(),
          new StaffPrincipal(UUID.randomUUID(), UUID.randomUUID(), UserRole.TEACHER),
          UserRole.TEACHER,
          Locale.ENGLISH,
          null);

  @BeforeEach
  void setUp() {
    ActionSupport actions =
        new ActionSupport(store, tokens, new AssistantProperties(), messages, toolSupport, JSON);
    tool = new MarkAttendanceTool(actions, attendance);
    lenient().when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
    lenient().when(tokens.generate()).thenReturn("tok");
  }

  @Test
  void previewResolvesAndPreparesWhenTeacherTeachesClass() {
    when(toolSupport.clazz(ctx, "5A")).thenReturn(new Resolved.Of<>(clazz()));
    when(toolSupport.teachesOrAdmin(ctx, classId)).thenReturn(true);
    when(toolSupport.student(ctx, "Ahmed")).thenReturn(new Resolved.Of<>(student()));

    PreviewOutcome outcome = tool.preview(args("5A", "Ahmed", "ABSENT"), ctx);

    assertThat(outcome).isInstanceOf(PreviewOutcome.Prepared.class);
    verify(store).put(any(), any());
  }

  @Test
  void previewDeniedWhenTeacherDoesNotTeachClass() {
    when(toolSupport.clazz(ctx, "5A")).thenReturn(new Resolved.Of<>(clazz()));
    when(toolSupport.teachesOrAdmin(ctx, classId)).thenReturn(false);

    PreviewOutcome outcome = tool.preview(args("5A", "Ahmed", "ABSENT"), ctx);

    assertThat(outcome).isInstanceOf(PreviewOutcome.Rejected.class);
    verify(store, never()).put(any(), any());
  }

  @Test
  void doExecuteReGuardsThenMarks() {
    when(toolSupport.teachesOrAdmin(ctx, classId)).thenReturn(true);
    ObjectNode resolved = JSON.createObjectNode();
    resolved.put("classId", classId.toString());
    resolved.put("studentId", studentId.toString());
    resolved.put("date", "2026-06-10");
    resolved.put("status", "ABSENT");

    tool.doExecute(resolved, ctx);

    org.mockito.ArgumentCaptor<MarkAttendanceRequest> captor =
        org.mockito.ArgumentCaptor.forClass(MarkAttendanceRequest.class);
    verify(attendance).mark(org.mockito.ArgumentMatchers.eq(ctx.userId()), captor.capture());
    assertThat(captor.getValue().classId()).isEqualTo(classId);
    assertThat(captor.getValue().studentId()).isEqualTo(studentId);
  }

  @Test
  void doExecuteDeniesWhenScopeLostBetweenPreviewAndConfirm() {
    when(toolSupport.teachesOrAdmin(ctx, classId)).thenReturn(false);
    ObjectNode resolved = JSON.createObjectNode();
    resolved.put("classId", classId.toString());
    resolved.put("studentId", studentId.toString());
    resolved.put("date", "2026-06-10");
    resolved.put("status", "ABSENT");

    ToolResult result = tool.doExecute(resolved, ctx);

    assertThat(result.status()).isEqualTo(ToolResult.Status.DENIED);
    verify(attendance, never()).mark(any(), any());
  }

  private SchoolClassResponse clazz() {
    return new SchoolClassResponse(
        classId, ctx.schoolId(), "5A", "Grade 5", "2025-2026", null, null, null);
  }

  private StudentResponse student() {
    return new StudentResponse(studentId, ctx.schoolId(), "Ahmed", null, null, null, null, null);
  }

  private com.fasterxml.jackson.databind.JsonNode args(
      String classRef, String studentRef, String status) {
    ObjectNode n = JSON.createObjectNode();
    n.put("classRef", classRef);
    n.put("studentRef", studentRef);
    n.put("status", status);
    n.put("date", "2026-06-10");
    return n;
  }
}
