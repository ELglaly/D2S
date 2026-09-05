package com.schoolbridge.api.attachments.dto;

import com.schoolbridge.api.attachments.AttachmentStatus;
import com.schoolbridge.api.attachments.AvResult;
import java.time.Instant;
import java.util.UUID;

/**
 * Attachment metadata.
 *
 * <p>{@code storageKey} is deliberately absent. It is an internal locator, it encodes the school
 * id, and exposing it invites clients to construct URLs against the bucket instead of asking for a
 * presigned one.
 */
public record AttachmentResponse(
    UUID id,
    String fileName,
    String contentType,
    Long sizeBytes,
    AttachmentStatus status,
    String rejectionReason,
    AvResult avResult,
    UUID uploaderUserId,
    Instant createdAt,
    Instant completedAt) {}
