package com.schoolbridge.api.assistant.tools.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.support.NameMatching.MatchResult;
import com.schoolbridge.api.classes.dto.ParentChildResponse;
import com.schoolbridge.api.common.i18n.MessageResolver;
import com.schoolbridge.api.common.security.PermissionsHelper;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolSupportTest {

  @Mock AssistantResolvers resolvers;
  @Mock MessageResolver messages;
  @Mock PermissionsHelper perms;

  private ToolSupport support() {
    return new ToolSupport(resolvers, messages, perms);
  }

  @Test
  void childResolvesUniqueByName() {
    ToolContext ctx = parentCtx();
    ParentChildResponse child = child("Ahmed");
    when(resolvers.children(ctx, "Ahmed")).thenReturn(new MatchResult<>(List.of(child)));

    Resolved<ParentChildResponse> r = support().child(ctx, "Ahmed");

    assertThat(r.clarified()).isFalse();
    assertThat(r.value()).isEqualTo(child);
  }

  @Test
  void childDefaultsToOnlyChildWhenNameOmitted() {
    ToolContext ctx = parentCtx();
    ParentChildResponse only = child("Sara");
    when(resolvers.childrenList(ctx)).thenReturn(List.of(only));

    Resolved<ParentChildResponse> r = support().child(ctx, null);

    assertThat(r.clarified()).isFalse();
    assertThat(r.value()).isEqualTo(only);
  }

  @Test
  void childClarifiesWhenNameOmittedAndMultipleChildren() {
    ToolContext ctx = parentCtx();
    when(resolvers.childrenList(ctx)).thenReturn(List.of(child("A"), child("B")));

    assertThat(support().child(ctx, null).clarified()).isTrue();
  }

  @Test
  void childClarifiesOnNoMatch() {
    ToolContext ctx = parentCtx();
    when(resolvers.children(ctx, "Zzz")).thenReturn(new MatchResult<>(List.of()));

    assertThat(support().child(ctx, "Zzz").clarified()).isTrue();
  }

  @Test
  void childClarifiesOnAmbiguous() {
    ToolContext ctx = parentCtx();
    when(resolvers.children(ctx, "Ahmed"))
        .thenReturn(new MatchResult<>(List.of(child("Ahmed Ali"), child("Ahmed Saleh"))));

    assertThat(support().child(ctx, "Ahmed").clarified()).isTrue();
  }

  @Test
  void teachesOrAdminShortCircuitsForAdmin() {
    UUID classId = UUID.randomUUID();
    assertThat(support().teachesOrAdmin(staffCtx(UserRole.SCHOOL_ADMIN), classId)).isTrue();
    verifyNoInteractions(perms);
  }

  @Test
  void teachesOrAdminDelegatesToPermsForTeacher() {
    UUID classId = UUID.randomUUID();
    when(perms.teacherTeaches(classId)).thenReturn(true);
    assertThat(support().teachesOrAdmin(staffCtx(UserRole.TEACHER), classId)).isTrue();
    verify(perms).teacherTeaches(classId);
  }

  @Test
  void teachesOrAdminDeniesTeacherOutOfScope() {
    UUID classId = UUID.randomUUID();
    when(perms.teacherTeaches(classId)).thenReturn(false);
    assertThat(support().teachesOrAdmin(staffCtx(UserRole.TEACHER), classId)).isFalse();
  }

  @Test
  void resolversNotConsultedForOnlyChildShortcut() {
    ToolContext ctx = parentCtx();
    when(resolvers.childrenList(ctx)).thenReturn(List.of(child("Solo")));
    support().child(ctx, "  ");
    verify(resolvers, never()).children(ctx, "  ");
  }

  private static ParentChildResponse child(String name) {
    return new ParentChildResponse(UUID.randomUUID(), name, null, null, null, false, List.of());
  }

  private static ToolContext parentCtx() {
    return new ToolContext(
        UUID.randomUUID(),
        new ParentPrincipal(UUID.randomUUID(), UUID.randomUUID()),
        UserRole.PARENT,
        Locale.ENGLISH,
        null);
  }

  private static ToolContext staffCtx(UserRole role) {
    return new ToolContext(
        UUID.randomUUID(),
        new StaffPrincipal(UUID.randomUUID(), UUID.randomUUID(), role),
        role,
        Locale.ENGLISH,
        null);
  }
}
