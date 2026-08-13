package com.schoolbridge.api.attachments.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A short-lived presigned download URL.
 *
 * <p>The URL is a bearer credential — anyone holding it before {@code expiresAt} can fetch the
 * object, with no further authorization. That is why the TTL is minutes rather than hours, and why
 * the URL is minted per request instead of stored.
 */
public record AttachmentDownloadTicket(
    UUID attachmentId,
    String downloadUrl,
    String fileName,
    String contentType,
    Instant expiresAt) {}
