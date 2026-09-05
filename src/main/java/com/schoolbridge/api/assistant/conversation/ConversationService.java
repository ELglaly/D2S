package com.schoolbridge.api.assistant.conversation;

import com.schoolbridge.api.assistant.conversation.dto.ConversationResponse;
import com.schoolbridge.api.assistant.conversation.dto.CreateConversationRequest;
import java.util.List;
import java.util.UUID;

/** CRUD for conversations, always scoped to the current tenant and the owning user. */
public interface ConversationService {

  ConversationResponse create(UUID schoolId, UUID ownerUserId, CreateConversationRequest request);

  List<ConversationResponse> listForOwner(UUID schoolId, UUID ownerUserId);

  void delete(UUID schoolId, UUID ownerUserId, UUID conversationId);

  /**
   * Loads a conversation the caller owns, or throws {@code NotFoundException} (anti-enumeration)
   * for a missing, foreign-tenant, or other-user conversation.
   */
  Conversation requireOwned(UUID schoolId, UUID ownerUserId, UUID conversationId);
}
