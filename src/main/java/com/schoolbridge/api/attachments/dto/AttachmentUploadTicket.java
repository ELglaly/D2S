package com.schoolbridge.api.attachments.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a client needs to perform the upload itself.
 *
 * <p>{@code requiredHeaders} is not advisory: the presigned URL's signature covers those headers,
 * so a PUT that omits or alters one is rejected by the object store with a 403 that looks like a
 * credentials failure and is not. Returning them explicitly is the difference between a usable API
 * and one where that 403 is the client's first clue.
 *
 * @param attachmentId pass this to {@code /attachments/{id}/complete} once the PUT succeeds
 * @param uploadUrl presigned target â€” the bytes go here, not to this API
 * @param method HTTP method the URL was signed for
 * @param requiredHeaders headers the PUT must carry verbatim
 * @param expiresAt after this the URL is dead and a new one must be requested
 */
public record AttachmentUploadTicket(
    UUID attachmentId,
    String uploadUrl,
    String method,
    Map<String, String> requiredHeaders,
    Instant expiresAt) {}

