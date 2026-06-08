package com.schoolbridge.api.common.error;

/**
 * Base type for all domain/application errors. Carries an {@link ErrorType} (→ HTTP status) and an
 * i18n message key with arguments so the global handler can localize the detail per request locale.
 */
public abstract class ApplicationException extends RuntimeException {

  private final ErrorType errorType;
  private final String messageKey;
  private final transient Object[] args;

  protected ApplicationException(ErrorType errorType, String messageKey, Object... args) {
    super(messageKey);
    this.errorType = errorType;
    this.messageKey = messageKey;
    this.args = args == null ? new Object[0] : args.clone();
  }

  public ErrorType errorType() {
    return errorType;
  }

  public String messageKey() {
    return messageKey;
  }

  public Object[] args() {
    return args.clone();
  }
}
