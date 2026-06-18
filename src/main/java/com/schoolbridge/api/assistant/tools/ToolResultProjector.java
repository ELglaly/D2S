package com.schoolbridge.api.assistant.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.schoolbridge.api.assistant.llm.AssistantProperties;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serializes a {@link ToolResult} into the compact JSON the model actually needs, in one auditable
 * place. Two reductions, both safe because the assistant addresses everything by name and never
 * passes identifiers back to a tool (tool inputs are {@code *Ref}/name fields resolved
 * server-side):
 *
 * <ul>
 *   <li><b>Identifier stripping</b> — drops {@code id}/{@code *Id}/{@code *Ids} fields and any
 *       UUID-valued field at every depth. This trims tokens and enforces the system-prompt
 *       guarantee that the model never sees internal identifiers (today's raw DTOs leak them).
 *   <li><b>Collection capping</b> — caps arrays at {@code tool-result-max-items}, appending a short
 *       marker so the model knows to narrow its query rather than miscount a truncated list.
 * </ul>
 */
@Component
public class ToolResultProjector {

  private static final Logger log = LoggerFactory.getLogger(ToolResultProjector.class);

  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private final ObjectMapper mapper;
  private final AssistantProperties properties;

  public ToolResultProjector(ObjectMapper mapper, AssistantProperties properties) {
    this.mapper = mapper;
    this.properties = properties;
  }

  /** The model-facing JSON for a tool result: id-free and collection-capped. */
  public String serialize(ToolResult result) {
    try {
      return mapper.writeValueAsString(prune(mapper.valueToTree(result)));
    } catch (Exception e) {
      // Includes checked JsonProcessingException; projection must never break the chat flow.
      log.warn("Failed to project tool result", e);
      return "{\"status\":\"ERROR\"}";
    }
  }

  private JsonNode prune(JsonNode node) {
    if (node.isObject()) {
      ObjectNode out = mapper.createObjectNode();
      for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> field = it.next();
        if (isIdentifierKey(field.getKey()) || isUuid(field.getValue())) {
          continue;
        }
        out.set(field.getKey(), prune(field.getValue()));
      }
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = mapper.createArrayNode();
      int cap = Math.max(1, properties.getToolResultMaxItems());
      int shown = Math.min(node.size(), cap);
      for (int i = 0; i < shown; i++) {
        out.add(prune(node.get(i)));
      }
      if (node.size() > cap) {
        out.add(TextNode.valueOf("…and " + (node.size() - cap) + " more; narrow your query"));
      }
      return out;
    }
    return node;
  }

  /** True for {@code id}, {@code uuid}, and camelCase {@code *Id}/{@code *Ids} keys. */
  private static boolean isIdentifierKey(String key) {
    return key.equals("id")
        || key.equals("uuid")
        || (key.length() > 2 && (key.endsWith("Id") || key.endsWith("Ids")));
  }

  private static boolean isUuid(JsonNode value) {
    return value.isTextual() && UUID_PATTERN.matcher(value.asText()).matches();
  }
}
