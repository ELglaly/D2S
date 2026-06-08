package com.schoolbridge.api.common.error;

/** Thrown when a caller exceeds an allowed request rate (→ 429). */
public class RateLimitException extends ApplicationException {

  public RateLimitException(String messageKey, Object... args) {
    super(ErrorType.RATE_LIMIT, messageKey, args);
  }

  public RateLimitException() {
    super(ErrorType.RATE_LIMIT, ErrorType.RATE_LIMIT.defaultMessageKey());
  }
}
