package com.schoolbridge.api.assistant.dto;

import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /assistant/actions/{token}/confirm}. {@code confirmation} carries the typed
 * reply ("yes"/"نعم") required for destructive actions when {@code
 * destructive-require-typed-confirm} is on; it is optional for non-destructive confirmations (a tap
 * is enough).
 */
public record ConfirmActionRequest(@Size(max = 50) String confirmation) {}
