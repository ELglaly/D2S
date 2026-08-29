package com.schoolbridge.api.common.error;

import org.springframework.http.HttpStatus;

/** Maps each application error category to an HTTP status and a default i18n title key. */
public enum ErrorType {
  NOT_FOUND(HttpStatus.NOT_FOUND, "error.not_found"),
  VALIDATION(HttpStatus.UNPROCESSABLE_ENTITY, "error.validation"),
  AUTHENTICATION(HttpStatus.UNAUTHORIZED, "error.unauthorized"),
  AUTHORIZATION(HttpStatus.FORBIDDEN, "error.forbidden"),
  CONFLICT(HttpStatus.CONFLICT, "error.conflict"),
  RATE_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "error.rate_limited"),
  INTEGRATION(HttpStatus.BAD_GATEWAY, "error.integration"),
  TENANT_SECURITY(HttpStatus.FORBIDDEN, "error.forbidden"),
  INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "error.internal");

  private final HttpStatus status;
  private final String defaultMessageKey;

  ErrorType(HttpStatus status, String defaultMessageKey) {
    this.status = status;
    this.defaultMessageKey = defaultMessageKey;
  }

  public HttpStatus status() {
    return status;
  }

  public String defaultMessageKey() {
    return defaultMessageKey;
  }

  /** Stable, dereferenceable problem type URI used in RFC 7807 responses. */
  public String typeUri() {
    return "https://schoolbridge.app/errors/" + name().toLowerCase().replace('_', '-');
  }
}

