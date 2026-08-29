package com.schoolbridge.api.assistant.settings.dto;

/**
 * The tenant's editable assistant persona. {@code systemPrompt} is null when the tenant uses the
 * default; {@code usingDefault} makes that explicit for the UI.
 */
public record AssistantSettingsResponse(String systemPrompt, boolean usingDefault) {}

