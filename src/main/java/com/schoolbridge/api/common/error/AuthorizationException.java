package com.schoolbridge.api.common.error;

/** Thrown when an authenticated principal lacks permission for an action (â†’ 403). */
public class AuthorizationException extends ApplicationException {

  public AuthorizationException(String messageKey, Object... args) {
    super(ErrorType.AUTHORIZATION, messageKey, args);
  }

  public AuthorizationException() {
    super(ErrorType.AUTHORIZATION, ErrorType.AUTHORIZATION.defaultMessageKey());
  }
}

