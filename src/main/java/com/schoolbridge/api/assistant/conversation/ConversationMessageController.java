package com.schoolbridge.api.assistant.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.AssistantContextFactory;
import com.schoolbridge.api.assistant.AssistantRateLimiter;
import com.schoolbridge.api.assistant.conversation.dto.SendMessageRequest;
import com.schoolbridge.api.assistant.llm.SystemPrompt;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.common.error.RateLimitException;
import com.schoolbridge.api.common.web.ApiConstants;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
 * Streaming chat endpoint: {@code POST /conversations/{id}/messages} opens an SSE connection and
 * streams the assistant's reply token-by-token in Anthropic's event shape. The reply is produced on
 * the request thread (tenant + security thread-locals stay valid); closing the HTTP connection
 * flips the stream to cancelled, which aborts the underlying LLM call. Rate limiting happens before
 * the stream opens so a 429 is a normal JSON error, not a mid-stream frame.
 */
@RestController
@RequestMapping(ApiConstants.API_V1 + "/conversations")
@ConditionalOnProperty(prefix = "schoolbridge.assistant", name = "enabled", havingValue = "true")
public class ConversationMessageController {

  private final ConversationChatService chatService;
  private final AssistantContextFactory contextFactory;
  private final AssistantRateLimiter rateLimiter;
  private final MeterRegistry meter;
  private final ObjectMapper mapper;

  public ConversationMessageController(
      ConversationChatService chatService,
      AssistantContextFactory contextFactory,
      AssistantRateLimiter rateLimiter,
      MeterRegistry meter,
      ObjectMapper mapper) {
    this.chatService = chatService;
    this.contextFactory = contextFactory;
    this.rateLimiter = rateLimiter;
    this.meter = meter;
    this.mapper = mapper;
  }

  @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public void send(
      @PathVariable UUID id,
      @Valid @RequestBody SendMessageRequest request,
      Authentication authentication,
      HttpServletResponse response)
      throws IOException {
    Locale language =
        SystemPrompt.detectLanguage(request.content(), LocaleContextHolder.getLocale());
    ToolContext ctx = contextFactory.fromAuthentication(authentication, language);
    if (!rateLimiter.tryAcquire(ctx.userId())) {
      throw new RateLimitException("error.rate_limited");
    }
    meter.counter("assistant.conversation.message").increment();

    response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    PrintWriter writer = response.getWriter();
    SseChatStream stream = new SseChatStream(writer, mapper);
    chatService.stream(ctx, id, request.content(), stream);
    writer.flush();
  }
}

