package com.schoolbridge.api.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.llm.AssistantProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ToolResultProjectorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  private final AssistantProperties properties = new AssistantProperties();
  private final ToolResultProjector projector = new ToolResultProjector(MAPPER, properties);

  private JsonNode parse(ToolResult result) {
    try {
      return MAPPER.readTree(projector.serialize(result));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void stripsIdAndUuidFieldsAtEveryDepth() {
    String uuid = UUID.randomUUID().toString();
    ToolResult result =
        ToolResult.ok(
            Map.of(
                "id",
                uuid,
                "studentId",
                uuid,
                "classIds",
                List.of(uuid),
                "fullName",
                "Layla Hassan",
                "owner",
                Map.of("teacherId", uuid, "name", "Mr Omar")));

    JsonNode data = parse(result).get("data");

    assertThat(data.has("id")).isFalse();
    assertThat(data.has("studentId")).isFalse();
    assertThat(data.has("classIds")).isFalse();
    assertThat(data.get("fullName").asText()).isEqualTo("Layla Hassan");
    assertThat(data.get("owner").has("teacherId")).isFalse();
    assertThat(data.get("owner").get("name").asText()).isEqualTo("Mr Omar");
  }

  @Test
  void keepsNonIdFieldsThatMerelyEndInId() {
    // "valid"/"grid" must not be mistaken for identifier keys.
    ToolResult result = ToolResult.ok(Map.of("valid", true, "grid", "A1"));

    JsonNode data = parse(result).get("data");

    assertThat(data.get("valid").asBoolean()).isTrue();
    assertThat(data.get("grid").asText()).isEqualTo("A1");
  }

  @Test
  void capsCollectionsAndMarksTruncation() {
    properties.setToolResultMaxItems(3);
    ToolResult result = ToolResult.ok(IntStream.range(0, 10).mapToObj(i -> "row" + i).toList());

    JsonNode data = parse(result).get("data");

    assertThat(data.size()).isEqualTo(4); // 3 rows + 1 marker
    assertThat(data.get(0).asText()).isEqualTo("row0");
    assertThat(data.get(3).asText()).contains("7 more");
  }

  @Test
  void omitsNullMessageOnOkResult() {
    JsonNode root = parse(ToolResult.ok(List.of("a", "b")));

    assertThat(root.get("status").asText()).isEqualTo("OK");
    assertThat(root.has("message")).isFalse();
  }
}
