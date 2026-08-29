package com.schoolbridge.api.attachments.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Asks for an upload URL.
 *
 * <p>Everything here is a client claim. {@code sizeBytes} is signed into the presigned PUT so the
 * object store enforces it; {@code contentType} is only ever checked for agreement with the type
 * sniffed from the stored bytes. Neither is believed on its own.
 *
 * @param fileName original name, shown to the user and echoed in the download disposition
 * @param contentType the type the client claims to be uploading
 * @param sizeBytes exact byte count the client intends to send
 */
public record CreateAttachmentRequest(
    @NotBlank @Size(max = 255) String fileName,
    @NotBlank @Size(max = 128) String contentType,
    // The real cap is schoolbridge.storage.max-upload-bytes, checked in the service against the
    // configured value. This bound only stops an absurd number from reaching that arithmetic.
    @Positive @Max(1_073_741_824L) long sizeBytes) {}

