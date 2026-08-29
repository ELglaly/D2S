package com.schoolbridge.api.assistant.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * A capability exposed to the LLM. Each tool is a thin adapter over an existing service.
 *
 * <p>{@link #permissions()} is the permission this tool requires and mirrors the backing endpoint's
 * {@code @RequirePermission}. A tool is offered to a caller iff the caller's role holds at least
 * one of these permissions in the DB-backed {@code role_permissions} grants (see {@link
 * ToolAuthorizer}) â€” the single source of truth. Fine-grained scope checks (e.g. {@code
 * teacherTeaches}, parent-link) run <em>inside</em> the tool at execution time, never here.
 */
public interface Tool {

  /** Stable snake_case identifier the model uses to call this tool. */
  String name();

  /** One-line description shown to the model. */
  String description();

  /** JSON Schema (object) describing the arguments the model may supply. */
  JsonNode inputSchema();

  ToolKind kind();

  /**
   * The permission(s) this tool requires (ANY-of). Mirrors the backing endpoint's
   * {@code @RequirePermission}; a caller may use the tool iff their role holds at least one.
   */
  Set<Permission> permissions();

  /** Intent bucket for query-based catalog gating; defaults from the tool's package. */
  default ToolDomain domain() {
    return ToolDomain.fromPackage(getClass());
  }
}

