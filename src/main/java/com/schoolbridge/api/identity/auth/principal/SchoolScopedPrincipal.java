package com.schoolbridge.api.identity.auth.principal;

import java.util.UUID;

/**
 * Marker for principals bound to a specific school — {@link
 * com.schoolbridge.api.common.tenancy.TenantContext} reads off this.
 */
public interface SchoolScopedPrincipal {
  UUID schoolId();
}
