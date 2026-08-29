package com.schoolbridge.api.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One content block in a model conversation, mirroring the Anthropic Messages content-block shapes
 * but decoupled from the SDK so the orchestrator and tests never depend on it.
 */
public sealed interface LlmContent {

  /** Plain text authored by the user or the model. */
  record Text(String text) implements LlmContent {}

  /** A model request to call a tool, with its generated arguments. */
  record ToolUse(String id, String name, JsonNode input) implements LlmContent {}

  /** The server's reply to a {@link ToolUse}, fed back to the model. */
  record ToolResult(String toolUseId, String content, boolean error) implements LlmContent {}
}

