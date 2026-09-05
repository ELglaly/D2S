package com.schoolbridge.api.assistant.confirm;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A previewed-but-not-executed action, stored single-use in Redis under its token. {@code
 * resolvedArgs} holds the server-resolved ids/values (never re-resolved at execute time, to avoid
 * drift); {@code impact} is the structured, non-secret summary surfaced to the client.
 */
public record PendingAction(
    String token,
    UUID userId,
    UUID schoolId,
    String toolName,
    JsonNode resolvedArgs,
    Map<String, Object> impact,
    boolean destructive,
    Instant createdAt,
    Instant expiresAt) {}
