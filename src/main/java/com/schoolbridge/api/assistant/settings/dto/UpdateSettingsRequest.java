package com.schoolbridge.api.assistant.settings.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /assistant/settings}. A null or blank {@code systemPrompt} clears the override
 * and reverts the tenant to the default persona.
 */
public record UpdateSettingsRequest(@Size(max = 8000) String systemPrompt) {}

