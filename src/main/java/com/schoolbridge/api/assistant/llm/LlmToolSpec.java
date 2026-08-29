package com.schoolbridge.api.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** A tool advertised to the model: name, description, and JSON-Schema for its arguments. */
public record LlmToolSpec(String name, String description, JsonNode inputSchema) {}

