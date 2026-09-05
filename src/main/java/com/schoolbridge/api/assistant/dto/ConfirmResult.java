package com.schoolbridge.api.assistant.dto;

/**
 * Result of a confirm/cancel call. {@code status} is one of EXECUTED, CANCELLED,
 * TYPED_CONFIRM_NEEDED, DENIED, or INVALID. {@code data} holds the created/affected entity when
 * EXECUTED.
 */
public record ConfirmResult(String status, String message, Object data) {

  public static ConfirmResult executed(String message, Object data) {
    return new ConfirmResult("EXECUTED", message, data);
  }

  public static ConfirmResult cancelled(String message) {
    return new ConfirmResult("CANCELLED", message, null);
  }

  public static ConfirmResult typedConfirmNeeded(String message) {
    return new ConfirmResult("TYPED_CONFIRM_NEEDED", message, null);
  }

  public static ConfirmResult denied(String message) {
    return new ConfirmResult("DENIED", message, null);
  }

  public static ConfirmResult invalid(String message) {
    return new ConfirmResult("INVALID", message, null);
  }
}
