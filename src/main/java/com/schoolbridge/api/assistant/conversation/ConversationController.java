package com.schoolbridge.api.assistant.conversation;

import com.schoolbridge.api.assistant.AssistantContextFactory;
import com.schoolbridge.api.assistant.conversation.dto.ConversationResponse;
import com.schoolbridge.api.assistant.conversation.dto.CreateConversationRequest;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.common.web.ApiConstants;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conversation lifecycle endpoints (loaded only when {@code schoolbridge.assistant.enabled=true}).
 * Every conversation is owned by exactly one user within a tenant; ownership is enforced in the
 * service. The streaming {@code POST /{id}/messages} endpoint lives in {@code
 * ConversationMessageController}.
 */
@RestController
@RequestMapping(ApiConstants.API_V1 + "/conversations")
@ConditionalOnProperty(prefix = "schoolbridge.assistant", name = "enabled", havingValue = "true")
public class ConversationController {

  private final ConversationService service;
  private final AssistantContextFactory contextFactory;

  public ConversationController(
      ConversationService service, AssistantContextFactory contextFactory) {
    this.service = service;
    this.contextFactory = contextFactory;
  }

  @PostMapping
  public ResponseEntity<ConversationResponse> create(
      @Valid @RequestBody(required = false) CreateConversationRequest request,
      Authentication authentication) {
    ToolContext ctx = contextFactory.fromAuthentication(authentication, locale());
    ConversationResponse created = service.create(ctx.schoolId(), ctx.userId(), request);
    return ResponseEntity.created(
            URI.create(ApiConstants.API_V1 + "/conversations/" + created.id()))
        .body(created);
  }

  @GetMapping
  public List<ConversationResponse> list(Authentication authentication) {
    ToolContext ctx = contextFactory.fromAuthentication(authentication, locale());
    return service.listForOwner(ctx.schoolId(), ctx.userId());
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
    ToolContext ctx = contextFactory.fromAuthentication(authentication, locale());
    service.delete(ctx.schoolId(), ctx.userId(), id);
    return ResponseEntity.noContent().build();
  }

  private static java.util.Locale locale() {
    return LocaleContextHolder.getLocale();
  }
}
