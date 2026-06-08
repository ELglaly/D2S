package com.schoolbridge.api.common.error;

/** Thrown when a request fails business validation (→ 422). */
public class ValidationException extends ApplicationException {

  public ValidationException(String messageKey, Object... args) {
    super(ErrorType.VALIDATION, messageKey, args);
  }

  public ValidationException() {
    super(ErrorType.VALIDATION, ErrorType.VALIDATION.defaultMessageKey());
  }
}
