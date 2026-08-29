package com.schoolbridge.api.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.audit.AssistantAuditRecorder;
import com.schoolbridge.api.assistant.dto.ActionPreview;
import com.schoolbridge.api.assistant.dto.AskRequest;
import com.schoolbridge.api.assistant.dto.AssistantAnswer;
import com.schoolbridge.api.assistant.dto.ConfirmActionRequest;
import com.schoolbridge.api.assistant.dto.ConfirmResult;
import com.schoolbridge.api.assistant.llm.SystemPrompt;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.common.error.RateLimitException;
import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.SchoolScopedPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Assistant endpoints (loaded only when {@code schoolbridge.assistant.enabled=true}):
 *
 * <ul>
 *   <li>{@code POST /ask} â€” the answer is computed synchronously (so tenant + security
 *       thread-locals stay valid) and the SSE frames are written straight to the response. Writing
 *       raw frames means the Jackson-based {@code ApiResponseBodyAdvice} never wraps them.
 *   <li>{@code POST /actions/{token}/confirm} and {@code /cancel} â€” normal wrapped JSON.
 * </ul>
 */
@RestController
@RequestMapping(ApiConstants.API_V1 + "/assistant")
@ConditionalOnProperty(prefix = "schoolbridge.assistant", name = "enabled", havingValue = "true")
public class AssistantController {

  private final AssistantService service;
  private final AssistantActionService actionService;
  private final AssistantRateLimiter rateLimiter;
  private final AssistantAuditRecorder recorder;
  private final ObjectMapper mapper;
  private final MeterRegistry meter;

  public AssistantController(
      AssistantService service,
      AssistantActionService actionService,
      AssistantRateLimiter rateLimiter,
      AssistantAuditRecorder recorder,
      ObjectMapper mapper,
      MeterRegistry meter) {
    this.service = service;
    this.actionService = actionService;
    this.rateLimiter = rateLimiter;
    this.recorder = recorder;
    this.mapper = mapper;
    this.meter = meter;
  }

  @PostMapping("/ask")
  public void ask(
      @Valid @RequestBody AskRequest request,
      Authentication authentication,
      HttpServletResponse response)
      throws IOException {
    ToolContext ctx = contextFrom(authentication, request);
    if (!rateLimiter.tryAcquire(ctx.userId())) {
      throw new RateLimitException("error.rate_limited");
    }
    AssistantAnswer answer = service.ask(request, ctx);
    meter.counter("assistant.ask", "outcome", answer.outcome().name()).increment();

    response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    PrintWriter writer = response.getWriter();
    switch (answer.outcome()) {
      case ANSWERED -> {
        frame(writer, "delta", Map.of("text", nullSafe(answer.text())));
        frame(writer, "done", Map.of("metadata", answer.metadata()));
        recorder.ask(ctx, answer);
      }
      case CONFIRM_REQUIRED -> {
        frame(writer, "confirmRequired", confirmChunk(answer.pendingAction()));
        recorder.preview(ctx, answer);
      }
      case ERROR -> frame(writer, "error", Map.of("message", nullSafe(answer.text())));
    }
    writer.flush();
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

  private void frame(PrintWriter writer, String type, Map<String, Object> data) throws IOException {
    Map<String, Object> chunk = new LinkedHashMap<>();
    chunk.put("type", type);
    chunk.putAll(data);
    writer.write("event: " + type + "\n");
    writer.write("data: " + mapper.writeValueAsString(chunk) + "\n\n");
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }
}

