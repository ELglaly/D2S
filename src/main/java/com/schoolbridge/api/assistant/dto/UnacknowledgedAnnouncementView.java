package com.schoolbridge.api.assistant.dto;

import java.time.Instant;
import java.util.UUID;

/** Compact parent-facing view of an announcement the parent has not yet acknowledged. */
public record UnacknowledgedAnnouncementView(
    UUID announcementId, UUID studentId, String body, boolean requiresAck, Instant receivedAt) {}

