package com.schoolbridge.api.assistant.tools;

import com.fasterxml.jackson.databind.JsonNode;

/** A non-mutating tool: validates scope, resolves names to ids, and returns queried data. */
public interface ReadTool extends Tool {

  ToolResult execute(JsonNode args, ToolContext ctx);

  @Override
  default ToolKind kind() {
    return ToolKind.READ;
  }
}
