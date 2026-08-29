package com.schoolbridge.api.assistant.settings;

import com.schoolbridge.api.assistant.llm.AssistantProperties;
import com.schoolbridge.api.assistant.llm.SystemPrompt;
import com.schoolbridge.api.assistant.tools.ToolContext;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the effective system prompt for a tenant. The editable part â€” a per-school persona
 * stored in {@code assistant_settings}, falling back to a configured default and then the built-in
 * persona â€” is always followed by the immutable security {@link SystemPrompt#guardrails
 * guardrails}, so a tenant's edited prompt can never weaken the confirm gate or cause the model to
 * leak ids.
 */
@Service
public class AssistantSettingsService {

  private final AssistantSettingsRepository repository;
  private final AssistantProperties properties;
  private final SystemPrompt systemPrompt;

  public AssistantSettingsService(
      AssistantSettingsRepository repository,
      AssistantProperties properties,
      SystemPrompt systemPrompt) {
    this.repository = repository;
    this.properties = properties;
    this.systemPrompt = systemPrompt;
  }

  /** The full system prompt: (tenant persona | configured default | built-in) + guardrails. */
  @Transactional(readOnly = true)
  public String resolveSystemPrompt(ToolContext ctx) {
    String persona =
        repository
            .findBySchoolId(ctx.schoolId())
            .map(AssistantSettings::getSystemPrompt)
            .filter(s -> s != null && !s.isBlank())
            .or(() -> nonBlank(properties.getDefaultSystemPrompt()))
            .orElseGet(() -> systemPrompt.defaultPersona(ctx));
    return persona.strip() + "\n\n" + systemPrompt.guardrails(ctx);
  }

  /** The raw editable persona for this tenant (null when none has been set). */
  @Transactional(readOnly = true)
  public String currentPersona(ToolContext ctx) {
    return repository
        .findBySchoolId(ctx.schoolId())
        .map(AssistantSettings::getSystemPrompt)
        .orElse(null);
  }

  /** Upserts the tenant's editable persona. A null/blank value clears it (revert to default). */
  @Transactional
  public void updateSystemPrompt(ToolContext ctx, String persona) {
    String value = persona == null || persona.isBlank() ? null : persona.strip();
    AssistantSettings settings =
        repository
            .findBySchoolId(ctx.schoolId())
            .orElseGet(() -> new AssistantSettings(ctx.schoolId(), null));
    settings.updateSystemPrompt(value);
    repository.save(settings);
  }

  private static Optional<String> nonBlank(String value) {
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
  }
}

