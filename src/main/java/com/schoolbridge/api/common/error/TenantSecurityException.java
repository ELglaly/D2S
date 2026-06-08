package com.schoolbridge.api.common.error;

/**
 * Thrown when a cross-tenant access attempt is detected (→ 403). The global handler writes a
 * dedicated high-severity audit entry for these.
 */
public class TenantSecurityException extends ApplicationException {

  public TenantSecurityException(String messageKey, Object... args) {
    super(ErrorType.TENANT_SECURITY, messageKey, args);
  }

  public TenantSecurityException() {
    super(ErrorType.TENANT_SECURITY, "error.tenant_security");
  }
}
