package com.schoolbridge.api.assistant.settings;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Per-tenant editable assistant configuration (one row per school). {@code systemPrompt} is the
 * editable persona/preamble only; the immutable security guardrails are always appended server-side
 * by {@code AssistantSettingsService} so an edited prompt can never weaken the confirm gate or leak
 * internal identifiers.
 */
@Entity
@Table(name = "assistant_settings")
public class AssistantSettings extends TenantEntity {

  @Column(name = "system_prompt", columnDefinition = "TEXT")
  private String systemPrompt;

  protected AssistantSettings() {}

  public AssistantSettings(UUID schoolId, String systemPrompt) {
    super(schoolId);
    this.systemPrompt = systemPrompt;
  }

  public void updateSystemPrompt(String systemPrompt) {
    this.systemPrompt = systemPrompt;
  }

  public String getSystemPrompt() {
    return systemPrompt;
  }
}
