package com.schoolbridge.api.common.security.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.schoolbridge.api.common.security.authz.RequirePermission.Mode;
import com.schoolbridge.api.identity.UserRole;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Pure-unit test for {@link PermissionAspect} — mocked service, no Spring context. */
class PermissionAspectTest {

  private final EffectivePermissionService permissions = mock(EffectivePermissionService.class);
  private final PermissionAspect aspect = new PermissionAspect(permissions);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  /** Fixture carrying real annotations so {@code Method.getAnnotation} returns them. */
  static class Fixture {
    @RequirePermission(Permission.GRADE_READ)
    Object single() {
      return "ok";
    }

    @RequirePermission(
        value = {Permission.GRADE_READ, Permission.GRADE_UPDATE},
        mode = Mode.ALL)
    Object both() {
      return "ok";
    }

    @RequirePermission(
        value = {Permission.GRADE_READ, Permission.GRADE_DELETE},
        mode = Mode.ANY)
    Object any() {
      return "ok";
    }
  }

  @Test
  void allowsWhenRoleHasTheRequiredPermission() throws Throwable {
    authenticateAs(UserRole.PARENT);
    when(permissions.permissionsForRole(eq(UserRole.PARENT))).thenReturn(Set.of("GRADE_READ"));

    assertThat(aspect.enforce(joinPointFor("single"))).isEqualTo("ok");
  }

  @Test
  void allowsModeAllWhenEveryPermissionPresent() throws Throwable {
    authenticateAs(UserRole.TEACHER);
    when(permissions.permissionsForRole(eq(UserRole.TEACHER)))
        .thenReturn(Set.of("GRADE_READ", "GRADE_UPDATE"));

    assertThat(aspect.enforce(joinPointFor("both"))).isEqualTo("ok");
  }

  @Test
  void deniesModeAllWhenOnePermissionMissing() throws Throwable {
    authenticateAs(UserRole.TEACHER);
    when(permissions.permissionsForRole(eq(UserRole.TEACHER))).thenReturn(Set.of("GRADE_READ"));

    assertThatThrownBy(() -> aspect.enforce(joinPointFor("both")))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void allowsModeAnyWhenOnePermissionPresent() throws Throwable {
    authenticateAs(UserRole.SCHOOL_ADMIN);
    when(permissions.permissionsForRole(eq(UserRole.SCHOOL_ADMIN)))
        .thenReturn(Set.of("GRADE_READ"));

    assertThat(aspect.enforce(joinPointFor("any"))).isEqualTo("ok");
  }

  @Test
  void rejectsUnauthenticatedCaller() {
    assertThatThrownBy(() -> aspect.enforce(joinPointFor("single")))
        .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
  }

  @Test
  void rejectsAnonymousCaller() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

    assertThatThrownBy(() -> aspect.enforce(joinPointFor("single")))
        .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
  }

  private void authenticateAs(UserRole role) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "user", null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
  }

  private ProceedingJoinPoint joinPointFor(String methodName) throws Throwable {
    Method method = Fixture.class.getDeclaredMethod(methodName);
    MethodSignature signature = mock(MethodSignature.class);
    when(signature.getMethod()).thenReturn(method);
    ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    when(joinPoint.getSignature()).thenReturn(signature);
    when(joinPoint.proceed()).thenReturn("ok");
    return joinPoint;
  }
}
