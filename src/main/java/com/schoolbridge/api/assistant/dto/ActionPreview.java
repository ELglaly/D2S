package com.schoolbridge.api.assistant.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Result of {@code ActionTool.preview(...)}: a single-use confirmation token plus a bilingual,
 * human-readable summary of the impact. No mutation has happened when this is produced — the caller
 * must confirm the token before {@code execute} runs.
 */
public record ActionPreview(
    String token,
    String summaryAr,
    String summaryEn,
    Map<String, Object> impact,
    boolean destructive,
    Instant expiresAt) {}
