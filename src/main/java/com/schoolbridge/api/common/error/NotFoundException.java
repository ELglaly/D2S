package com.schoolbridge.api.common.error;

/** Thrown when a requested resource does not exist (→ 404). */
public class NotFoundException extends ApplicationException {

  public NotFoundException(String messageKey, Object... args) {
    super(ErrorType.NOT_FOUND, messageKey, args);
  }

  public NotFoundException() {
    super(ErrorType.NOT_FOUND, ErrorType.NOT_FOUND.defaultMessageKey());
  }
}
