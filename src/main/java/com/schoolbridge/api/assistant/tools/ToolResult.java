package com.schoolbridge.api.assistant.tools;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Typed result of a tool invocation, serialized to JSON and fed back to the model as a tool_result.
 * The model never sees Java exceptions or stack traces — only one of these four shapes.
 *
 * <p>{@code NON_NULL} keeps the JSON the model sees minimal: an OK result omits {@code message},
 * and a DENIED/CLARIFY/ERROR result omits {@code data}, trimming tokens on every tool result.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(Status status, Object data, String message) {

  public enum Status {
    /** Succeeded; {@code data} holds the queried payload. */
    OK,
    /** Caller's role/scope does not permit this; {@code message} explains. */
    DENIED,
    /** Needs more information (no/ambiguous name match); {@code message} asks for it. */
    CLARIFY,
    /** Unexpected failure; {@code message} is a localized, non-leaking summary. */
    ERROR
  }

  public static ToolResult ok(Object data) {
    return new ToolResult(Status.OK, data, null);
  }

  public static ToolResult denied(String message) {
    return new ToolResult(Status.DENIED, null, message);
  }

  public static ToolResult clarify(String message) {
    return new ToolResult(Status.CLARIFY, null, message);
  }

  public static ToolResult error(String message) {
    return new ToolResult(Status.ERROR, null, message);
  }

  public boolean isOk() {
    return status == Status.OK;
  }
}
