package com.schoolbridge.api.common.error;

/** Thrown when authentication is missing or invalid (→ 401). */
public class AuthenticationException extends ApplicationException {

  public AuthenticationException(String messageKey, Object... args) {
    super(ErrorType.AUTHENTICATION, messageKey, args);
  }

  public AuthenticationException() {
    super(ErrorType.AUTHENTICATION, ErrorType.AUTHENTICATION.defaultMessageKey());
  }
}
