package com.schoolbridge.api.assistant.conversation.dto;

import com.schoolbridge.api.assistant.conversation.Conversation;
import java.time.Instant;
import java.util.UUID;

/** Summary view of a conversation returned by create/list. */
public record ConversationResponse(
    UUID id, String title, Instant lastMessageAt, Instant createdAt) {

  public static ConversationResponse from(Conversation c) {
    return new ConversationResponse(
        c.getId(), c.getTitle(), c.getLastMessageAt(), c.getCreatedAt());
  }
}

