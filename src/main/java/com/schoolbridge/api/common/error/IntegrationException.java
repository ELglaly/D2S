package com.schoolbridge.api.common.error;

/**
 * Thrown when a downstream/third-party integration fails (â†’ 502). Detail returned to clients is
 * sanitized; the underlying cause is logged server-side only.
 */
public class IntegrationException extends ApplicationException {

  public IntegrationException(String messageKey, Throwable cause, Object... args) {
    super(ErrorType.INTEGRATION, messageKey, args);
    initCause(cause);
  }

  public IntegrationException(String messageKey, Object... args) {
    super(ErrorType.INTEGRATION, messageKey, args);
  }

  public IntegrationException() {
    super(ErrorType.INTEGRATION, ErrorType.INTEGRATION.defaultMessageKey());
  }
}

