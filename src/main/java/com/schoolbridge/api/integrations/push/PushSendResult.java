package com.schoolbridge.api.integrations.push;

/** Result of a single push notification attempt. */
public record PushSendResult(boolean accepted, String messageId) {}

