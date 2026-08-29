package com.schoolbridge.api.common.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission(s) a caller must hold to invoke the annotated method (or any method of
 * the annotated type). Enforced by {@link PermissionAspect}.
 *
 * <p>Works on controllers and services alike because both are Spring beans and therefore proxied.
 * Self-invocation (a bean calling its own annotated method) bypasses the proxy and is NOT
 * intercepted â€” call across beans.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

  /** The permission(s) checked against the caller's effective permissions. */
  Permission[] value();

  /** {@link Mode#ALL} (default) requires every listed permission; {@link Mode#ANY} requires one. */
  Mode mode() default Mode.ALL;

  enum Mode {
    ALL,
    ANY
  }
}

