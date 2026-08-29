package com.schoolbridge.api.assistant.tools;

import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import com.schoolbridge.api.identity.auth.principal.SchoolScopedPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import java.util.Locale;
import java.util.UUID;

/**
 * Per-request context handed to every tool. Carries the resolved tenant, the authenticated
 * principal, the role, the answer language, and (for action execution) a stable idempotency key.
 *
 * <p>Immutable â€” {@link #withIdempotencyKey(String)} returns a copy. The {@code schoolId} always
 * comes from {@code TenantContext.require()} and never from the LLM.
 */
public record ToolContext(
    UUID schoolId,
    SchoolScopedPrincipal principal,
    UserRole role,
    Locale language,
    String idempotencyKey) {

  public boolean isParent() {
    return principal instanceof ParentPrincipal;
  }

  public boolean isStaff() {
    return principal instanceof StaffPrincipal;
  }

  public boolean isAdmin() {
    return role == UserRole.SCHOOL_ADMIN;
  }

  public boolean isTeacher() {
    return role == UserRole.TEACHER;
  }

  /** The authenticated user's id, regardless of principal type. */
  public UUID userId() {
    if (principal instanceof StaffPrincipal staff) {
      return staff.userId();
    }
    if (principal instanceof ParentPrincipal parent) {
      return parent.userId();
    }
    throw new TenantSecurityException();
  }

  public StaffPrincipal staff() {
    if (principal instanceof StaffPrincipal staff) {
      return staff;
    }
    throw new TenantSecurityException();
  }

  public ParentPrincipal parent() {
    if (principal instanceof ParentPrincipal parent) {
      return parent;
    }
    throw new TenantSecurityException();
  }

  public ToolContext withIdempotencyKey(String key) {
    return new ToolContext(schoolId, principal, role, language, key);
  }
}

