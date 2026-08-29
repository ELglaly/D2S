package com.schoolbridge.api.common.tenancy;

import com.schoolbridge.api.common.error.TenantSecurityException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Holds the current request's tenant (school) id in a thread-local. Set by the auth/tenant filter
 * and cleared after each request. Background jobs operate on a tenant explicitly via {@link
 * #runAs(UUID, Supplier)}.
 */
public final class TenantContext {

  private static final ThreadLocal<UUID> CURRENT = new InheritableThreadLocal<>();

  private TenantContext() {}

  public static void set(UUID schoolId) {
    CURRENT.set(schoolId);
  }

  public static Optional<UUID> get() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** Returns the current tenant or throws a tenant-security error if none is bound. */
  public static UUID require() {
    UUID schoolId = CURRENT.get();
    if (schoolId == null) {
      throw new TenantSecurityException();
    }
    return schoolId;
  }

  public static void clear() {
    CURRENT.remove();
  }

  /** Runs an action bound to a specific tenant, restoring any previous tenant afterwards. */
  public static <T> T runAs(UUID schoolId, Supplier<T> action) {
    UUID previous = CURRENT.get();
    CURRENT.set(schoolId);
    try {
      return action.get();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }
}

