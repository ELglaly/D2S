package com.schoolbridge.api.assistant.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A mutating tool. Two-phase by contract:
 *
 * <ol>
 *   <li>{@link #preview} validates scope, resolves namesâ†’ids, computes a human impact, and stores
 *       a single-use pending action. It NEVER mutates. Returns a {@link PreviewOutcome}: a prepared
 *       confirmation, or a denied/clarify rejection fed back to the model.
 *   <li>{@link #execute} is reachable only via {@code /confirm} with a valid token. It re-validates
 *       scope, then mutates through the existing service using the caller's principal and a stable
 *       idempotency key.
 * </ol>
 */
public interface ActionTool extends Tool {

  PreviewOutcome preview(JsonNode args, ToolContext ctx);

  ToolResult execute(String token, ToolContext ctx);

  @Override
  default ToolKind kind() {
    return ToolKind.ACTION;
  }

  /** Destructive actions (delete_*, recall_*) require a typed confirmation, not a tap. */
  default boolean destructive() {
    return false;
  }
}
