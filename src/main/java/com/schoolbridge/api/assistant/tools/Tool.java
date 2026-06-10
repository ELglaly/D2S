package com.schoolbridge.api.assistant.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.identity.UserRole;
import java.util.Set;

/**
 * A capability exposed to the LLM. Each tool is a thin adapter over an existing service.
 *
 * <p>{@link #roles()} is the coarse availability gate and mirrors the backing endpoint's role
 * guard: a tool is offered to the model only when {@link ToolContext#role()} is in this set.
 * Fine-grained scope checks (e.g. {@code teacherTeaches}, parent-link) run <em>inside</em> the tool
 * at execution time, never here.
 */
public interface Tool {

  /** Stable snake_case identifier the model uses to call this tool. */
  String name();

  /** One-line description shown to the model. */
  String description();

  /** JSON Schema (object) describing the arguments the model may supply. */
  JsonNode inputSchema();

  ToolKind kind();

  /** Roles for which this tool is registered. */
  Set<UserRole> roles();
}
