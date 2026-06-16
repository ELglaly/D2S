package com.schoolbridge.api.assistant.conversation.dto;

import jakarta.validation.constraints.Size;

/** Body of {@code POST /conversations}. {@code title} is optional. */
public record CreateConversationRequest(@Size(max = 200) String title) {}
