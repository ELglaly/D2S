package com.schoolbridge.api.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link LlmGateway} backed by the Google Gemini API (Google AI Studio). Loaded only when {@code
 * schoolbridge.assistant.enabled=true} and {@code provider=gemini}.
 *
 * <p>Gemini links function responses by name, not by UUID. The gateway therefore sets {@code
 * LlmContent.ToolUse.id = functionName} so the orchestrator's echoed {@code toolUseId} can be used
 * directly as the {@code FunctionResponse} name without any external id-to-name mapping.
 */
@Component
@ConditionalOnExpression(
    "'${schoolbridge.assistant.provider:anthropic}'.equals('gemini')"
        + " and ${schoolbridge.assistant.enabled:false}"
        + " and '${schoolbridge.assistant.engine:native}'.equals('native')")
public class GeminiLlmGateway implements LlmGateway {

  private final Client client;
  private final ObjectMapper mapper;

  public GeminiLlmGateway(Client client, ObjectMapper mapper) {
    this.client = client;
    this.mapper = mapper;
  }

  @Override
  @CircuitBreaker(name = "assistant")
  public LlmResponse converse(LlmRequest request) {
    List<FunctionDeclaration> declarations =
        request.tools().stream().map(this::toFunctionDeclaration).toList();

    Content sysContent = Content.builder().parts(Part.fromText(request.system())).build();
    GenerateContentConfig.Builder configBuilder =
        GenerateContentConfig.builder()
            .systemInstruction(sysContent)
            .maxOutputTokens((int) request.maxTokens());
    if (!declarations.isEmpty()) {
      configBuilder.tools(Tool.builder().functionDeclarations(declarations).build());
    }

    List<Content> contents = request.messages().stream().map(this::toContent).toList();

    GenerateContentResponse response =
        client.models.generateContent(request.model(), contents, configBuilder.build());

    return toResponse(response);
  }

  private FunctionDeclaration toFunctionDeclaration(LlmToolSpec spec) {
    @SuppressWarnings("unchecked")
    Map<String, Object> schemaMap = mapper.convertValue(spec.inputSchema(), Map.class);
    return FunctionDeclaration.builder()
        .name(spec.name())
        .description(spec.description())
        .parametersJsonSchema(schemaMap)
        .build();
  }

  private Content toContent(LlmMessage message) {
    String role = message.role() == LlmMessage.Role.USER ? "user" : "model";
    List<Part> parts = message.content().stream().map(this::toPart).toList();
    return Content.builder().role(role).parts(parts).build();
  }

  private Part toPart(LlmContent content) {
    if (content instanceof LlmContent.Text text) {
      return Part.fromText(text.text());
    }
    if (content instanceof LlmContent.ToolUse toolUse) {
      @SuppressWarnings("unchecked")
      Map<String, Object> args =
          toolUse.input() != null ? mapper.convertValue(toolUse.input(), Map.class) : Map.of();
      return Part.fromFunctionCall(toolUse.name(), args);
    }
    LlmContent.ToolResult result = (LlmContent.ToolResult) content;
    // toolUseId is the function name (set in toResponse); Gemini matches responses by name
    Map<String, Object> responseMap =
        result.error() ? Map.of("error", result.content()) : Map.of("output", result.content());
    return Part.fromFunctionResponse(result.toolUseId(), responseMap);
  }

  private LlmResponse toResponse(GenerateContentResponse response) {
    List<LlmContent> content = new ArrayList<>();

    List<FunctionCall> functionCalls = response.functionCalls();
    if (functionCalls != null) {
      for (FunctionCall fc : functionCalls) {
        String name = fc.name().orElse("");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = fc.args().orElseGet(Map::of);
        JsonNode input = mapper.valueToTree(args);
        // Use function name as id — Gemini matches FunctionResponse by name, not by UUID
        content.add(new LlmContent.ToolUse(name, name, input));
      }
    }

    List<Part> parts = response.parts();
    if (parts != null) {
      for (Part part : parts) {
        part.text()
            .ifPresent(
                t -> {
                  if (!t.isBlank()) content.add(new LlmContent.Text(t));
                });
      }
    }

    String stopReason =
        content.stream().anyMatch(LlmContent.ToolUse.class::isInstance) ? "tool_use" : "end_turn";

    LlmUsage usage =
        response
            .usageMetadata()
            .map(
                m ->
                    new LlmUsage(
                        m.promptTokenCount().orElse(0), m.candidatesTokenCount().orElse(0)))
            .orElse(LlmUsage.zero());

    return new LlmResponse(content, stopReason, usage);
  }
}
