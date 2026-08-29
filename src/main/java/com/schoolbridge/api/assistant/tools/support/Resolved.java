package com.schoolbridge.api.assistant.tools.support;

import com.schoolbridge.api.assistant.tools.ToolResult;

/**
 * Outcome of resolving an LLM-supplied name to a tenant-scoped entity: either a unique match, or a
 * {@link ToolResult} the tool should return immediately (missing / not-found / ambiguous name).
 */
public sealed interface Resolved<C> {

  /** A unique match. */
  record Of<C>(C value) implements Resolved<C> {}

  /** No usable match; carries the clarify {@link ToolResult} to return to the model. */
  record NeedsClarification<C>(ToolResult result) implements Resolved<C> {}

  default boolean clarified() {
    return this instanceof NeedsClarification<C>;
  }

  default ToolResult result() {
    return ((NeedsClarification<C>) this).result();
  }

  default C value() {
    return ((Of<C>) this).value();
  }
}

