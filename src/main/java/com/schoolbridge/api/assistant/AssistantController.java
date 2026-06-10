package com.schoolbridge.api.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.audit.AssistantAuditRecorder;
import com.schoolbridge.api.assistant.dto.ActionPreview;
import com.schoolbridge.api.assistant.dto.AskRequest;
import com.schoolbridge.api.assistant.dto.AssistantAnswer;
import com.schoolbridge.api.assistant.dto.ConfirmActionRequest;
import com.schoolbridge.api.assistant.dto.ConfirmResult;
import com.schoolbridge.api.assistant.llm.AssistantProperties;
import com.schoolbridge.api.assistant.llm.SystemPrompt;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.common.error.RateLimitException;
import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.common.i18n.MessageResolver;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.SchoolScopedPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Assistant endpoints (loaded only when {@code schoolbridge.assistant.enabled=true}):
 *
 * <ul>
 *   <li>{@code POST /ask} — SSE; a read answer, a confirmation request, or an error. Frames are
 *       sent as pre-serialized JSON strings so {@code ApiResponseBodyAdvice} (Jackson-only) never
 *       wraps them.
 *   <li>{@code POST /actions/{token}/confirm} and {@code /cancel} — normal wrapped JSON.
 * </ul>
 */
@RestController
@RequestMapping(ApiConstants.API_V1 + "/assistant")
@ConditionalOnProperty(prefix = "schoolbridge.assistant", name = "enabled", havingValue = "true")
public class AssistantController {

  private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

  private final AssistantService service;
  private final AssistantActionService actionService;
  private final AssistantRateLimiter rateLimiter;
  private final AssistantAuditRecorder recorder;
  private final AssistantProperties properties;
  private final MessageResolver messages;
  private final ObjectMapper mapper;
  private final MeterRegistry meter;

  public AssistantController(
      AssistantService service,
      AssistantActionService actionService,
      AssistantRateLimiter rateLimiter,
      AssistantAuditRecorder recorder,
      AssistantProperties properties,
      MessageResolver messages,
      ObjectMapper mapper,
      MeterRegistry meter) {
    this.service = service;
    this.actionService = actionService;
    this.rateLimiter = rateLimiter;
    this.recorder = recorder;
    this.properties = properties;
    this.messages = messages;
    this.mapper = mapper;
    this.meter = meter;
  }

  @PostMapping("/ask")
  public SseEmitter ask(@Valid @RequestBody AskRequest request, Authentication authentication) {
    ToolContext ctx = contextFrom(authentication, request);
    if (!rateLimiter.tryAcquire(ctx.userId())) {
      throw new RateLimitException("error.rate_limited");
    }
    SseEmitter emitter = new SseEmitter(properties.getRequestTimeout().toMillis() + 10_000);
    try {
      AssistantAnswer answer = service.ask(request, ctx);
      meter.counter("assistant.ask", "outcome", answer.outcome().name()).increment();
      switch (answer.outcome()) {
        case ANSWERED -> {
          send(emitter, "delta", Map.of("text", nullSafe(answer.text())));
          send(emitter, "done", Map.of("metadata", answer.metadata()));
          recorder.ask(ctx, answer);
        }
        case CONFIRM_REQUIRED -> {
          send(emitter, "confirmRequired", confirmChunk(answer.pendingAction()));
          recorder.preview(ctx, answer);
        }
        case ERROR -> send(emitter, "error", Map.of("message", nullSafe(answer.text())));
      }
      emitter.complete();
    } catch (RuntimeException | IOException e) {
      log.warn("Assistant /ask failed", e);
      trySend(emitter, "error", Map.of("message", messages.get("error.internal")));
      emitter.complete();
    }
    return emitter;
  }

  @PostMapping("/actions/{token}/confirm")
  public ConfirmResult confirm(
      @PathVariable String token,
      @RequestBody(required = false) @Valid ConfirmActionRequest body,
      Authentication authentication) {
    ToolContext ctx = contextFrom(authentication, null);
    ConfirmResult result = actionService.confirm(token, body, ctx);
    meter.counter("assistant.action.confirm", "status", result.status()).increment();
    return result;
  }

  @PostMapping("/actions/{token}/cancel")
  public ConfirmResult cancel(@PathVariable String token, Authentication authentication) {
    ToolContext ctx = contextFrom(authentication, null);
    ConfirmResult result = actionService.cancel(token, ctx);
    meter.counter("assistant.action.cancel").increment();
    return result;
  }

  // --- helpers --------------------------------------------------------------

  private ToolContext contextFrom(Authentication authentication, AskRequest request) {
    UUID schoolId = TenantContext.require();
    SchoolScopedPrincipal principal = principal(authentication);
    UserRole role = principal instanceof StaffPrincipal staff ? staff.role() : UserRole.PARENT;
    return new ToolContext(schoolId, principal, role, language(request), null);
  }

  private SchoolScopedPrincipal principal(Authentication authentication) {
    if (authentication != null
        && authentication.getPrincipal() instanceof SchoolScopedPrincipal principal) {
      return principal;
    }
    throw new TenantSecurityException();
  }

  private Locale language(AskRequest request) {
    if (request != null && request.language() != null && !request.language().isBlank()) {
      return Locale.forLanguageTag(request.language());
    }
    Locale fallback = LocaleContextHolder.getLocale();
    return request == null ? fallback : SystemPrompt.detectLanguage(request.question(), fallback);
  }

  private Map<String, Object> confirmChunk(ActionPreview preview) {
    Map<String, Object> chunk = new LinkedHashMap<>();
    chunk.put("token", preview.token());
    chunk.put("summary", preview.summaryAr());
    chunk.put("summaryEn", preview.summaryEn());
    chunk.put("impact", preview.impact());
    chunk.put("destructive", preview.destructive());
    chunk.put("expiresAt", preview.expiresAt().toString());
    return chunk;
  }

  private void send(SseEmitter emitter, String type, Map<String, Object> data) throws IOException {
    Map<String, Object> chunk = new LinkedHashMap<>();
    chunk.put("type", type);
    chunk.putAll(data);
    emitter.send(SseEmitter.event().name(type).data(mapper.writeValueAsString(chunk)));
  }

  private void trySend(SseEmitter emitter, String type, Map<String, Object> data) {
    try {
      send(emitter, type, data);
    } catch (IOException ignored) {
      // Client gone or stream closed — nothing more we can do.
    }
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }
}
