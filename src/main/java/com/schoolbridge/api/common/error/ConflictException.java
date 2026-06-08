package com.schoolbridge.api.common.error;

/** Thrown when a request conflicts with current resource state (→ 409). */
public class ConflictException extends ApplicationException {

  public ConflictException(String messageKey, Object... args) {
    super(ErrorType.CONFLICT, messageKey, args);
  }

  public ConflictException() {
    super(ErrorType.CONFLICT, ErrorType.CONFLICT.defaultMessageKey());
  }
}
