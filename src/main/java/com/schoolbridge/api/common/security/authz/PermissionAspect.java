package com.schoolbridge.api.common.security.authz;

import com.schoolbridge.api.identity.UserRole;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Enforces {@link RequirePermission}. Intercepts any bean method (or method of a type) carrying the
 * annotation, resolves the caller's single role from the {@code ROLE_*} authority already present
 * on the {@link Authentication}, looks up that role's cached permissions, and allows or denies.
 *
 * <ul>
 *   <li>No / anonymous authentication â†’ {@link AuthenticationCredentialsNotFoundException} (â†’
 *       401).
 *   <li>Authenticated but missing the required permission(s) â†’ {@link AccessDeniedException} (â†’
 *       403, via {@code GlobalExceptionHandler}).
 * </ul>
 */
@Aspect
@Component
public class PermissionAspect {

  private static final String ROLE_PREFIX = "ROLE_";

  private final EffectivePermissionService permissions;

  public PermissionAspect(EffectivePermissionService permissions) {
    this.permissions = permissions;
  }

  @Around(
      "@annotation(com.schoolbridge.api.common.security.authz.RequirePermission)"
          + " || @within(com.schoolbridge.api.common.security.authz.RequirePermission)")
  public Object enforce(ProceedingJoinPoint joinPoint) throws Throwable {
    RequirePermission annotation = resolve(joinPoint);
    if (annotation == null) {
      return joinPoint.proceed();
    }

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
      throw new AuthenticationCredentialsNotFoundException("error.unauthorized");
    }

    UserRole role = extractRole(auth);
    if (role == null) {
      throw new AccessDeniedException("error.forbidden");
    }

    Set<String> granted = permissions.permissionsForRole(role);
    Set<String> required =
        Arrays.stream(annotation.value()).map(Enum::name).collect(Collectors.toSet());

    boolean allowed =
        switch (annotation.mode()) {
          case ALL -> granted.containsAll(required);
          case ANY -> required.stream().anyMatch(granted::contains);
        };

    if (!allowed) {
      throw new AccessDeniedException("error.forbidden");
    }
    return joinPoint.proceed();
  }

  /** Method-level annotation wins; otherwise fall back to the declaring type's annotation. */
  private RequirePermission resolve(ProceedingJoinPoint joinPoint) {
    Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
    RequirePermission onMethod = method.getAnnotation(RequirePermission.class);
    if (onMethod != null) {
      return onMethod;
    }
    return method.getDeclaringClass().getAnnotation(RequirePermission.class);
  }

  private UserRole extractRole(Authentication auth) {
    for (GrantedAuthority authority : auth.getAuthorities()) {
      String value = authority.getAuthority();
      if (value != null && value.startsWith(ROLE_PREFIX)) {
        try {
          return UserRole.valueOf(value.substring(ROLE_PREFIX.length()));
        } catch (IllegalArgumentException ignored) {
          // Not a UserRole authority; keep scanning.
        }
      }
    }
    return null;
  }
}
