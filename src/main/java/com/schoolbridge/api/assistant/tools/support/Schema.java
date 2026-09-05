package com.schoolbridge.api.assistant.tools.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Tiny fluent builder for the JSON-Schema object an LLM tool advertises for its arguments. */
public final class Schema {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final ObjectNode properties = F.objectNode();
  private final ArrayNode required = F.arrayNode();

  private Schema() {}

  public static Schema builder() {
    return new Schema();
  }

  /** An object schema with no properties (for no-arg tools). */
  public static JsonNode empty() {
    return builder().build();
  }

  public Schema str(String name, String description, boolean req) {
    return prop(name, "string", description, req, null, null);
  }

  public Schema date(String name, String description, boolean req) {
    return prop(name, "string", description, req, "date", null);
  }

  public Schema integer(String name, String description, boolean req) {
    return prop(name, "integer", description, req, null, null);
  }

  public Schema number(String name, String description, boolean req) {
    return prop(name, "number", description, req, null, null);
  }

  public Schema bool(String name, String description, boolean req) {
    return prop(name, "boolean", description, req, null, null);
  }

  public Schema enumStr(String name, String description, boolean req, List<String> values) {
    ArrayNode en = F.arrayNode();
    values.forEach(en::add);
    return prop(name, "string", description, req, null, en);
  }

  private Schema prop(
      String name, String type, String description, boolean req, String format, ArrayNode en) {
    ObjectNode p = F.objectNode();
    p.put("type", type);
    if (description != null) {
      p.put("description", description);
    }
    if (format != null) {
      p.put("format", format);
    }
    if (en != null) {
      p.set("enum", en);
    }
    properties.set(name, p);
    if (req) {
      required.add(name);
    }
    return this;
  }

  public JsonNode build() {
    ObjectNode root = F.objectNode();
    root.put("type", "object");
    root.set("properties", properties);
    if (!required.isEmpty()) {
      root.set("required", required);
    }
    return root;
  }
}
