package com.schoolbridge.api.attendance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Parent reply to an absence alert. Plaintext limit of 500 chars; AES-GCM encrypted on persistence
 * (column is 1024 bytes to accommodate IV + tag + Base64 overhead).
 */
public record ParentResponseRequest(@NotBlank @Size(max = 500) String response) {}

