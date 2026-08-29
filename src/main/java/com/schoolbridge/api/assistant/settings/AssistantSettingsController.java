package com.schoolbridge.api.assistant.settings;

import com.schoolbridge.api.assistant.AssistantContextFactory;
import com.schoolbridge.api.assistant.settings.dto.AssistantSettingsResponse;
import com.schoolbridge.api.assistant.settings.dto.UpdateSettingsRequest;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.common.web.ApiConstants;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCHOOL_ADMIN-only management of the tenant's editable assistant persona. The immutable security
 * guardrails are never exposed or editable here â€” they are always appended at prompt-build time.
 */
@RestController
@RequestMapping(ApiConstants.API_V1 + "/assistant/settings")
@ConditionalOnProperty(prefix = "schoolbridge.assistant", name = "enabled", havingValue = "true")
public class AssistantSettingsController {

  private final AssistantSettingsService service;
  private final AssistantContextFactory contextFactory;

  public AssistantSettingsController(
      AssistantSettingsService service, AssistantContextFactory contextFactory) {
    this.service = service;
    this.contextFactory = contextFactory;
  }

  @GetMapping
  @PreAuthorize("hasRole('SCHOOL_ADMIN')")
  public AssistantSettingsResponse get(Authentication authentication) {
    ToolContext ctx =
        contextFactory.fromAuthentication(authentication, LocaleContextHolder.getLocale());
    String persona = service.currentPersona(ctx);
    return new AssistantSettingsResponse(persona, persona == null || persona.isBlank());
  }

  @PutMapping
  @PreAuthorize("hasRole('SCHOOL_ADMIN')")
  public AssistantSettingsResponse update(
      @Valid @RequestBody UpdateSettingsRequest request, Authentication authentication) {
    ToolContext ctx =
        contextFactory.fromAuthentication(authentication, LocaleContextHolder.getLocale());
    service.updateSystemPrompt(ctx, request.systemPrompt());
    String persona = service.currentPersona(ctx);
    return new AssistantSettingsResponse(persona, persona == null || persona.isBlank());
  }
}

