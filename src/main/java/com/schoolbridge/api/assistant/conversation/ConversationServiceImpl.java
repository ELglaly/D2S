package com.schoolbridge.api.assistant.conversation;

import com.schoolbridge.api.assistant.conversation.dto.ConversationResponse;
import com.schoolbridge.api.assistant.conversation.dto.CreateConversationRequest;
import com.schoolbridge.api.common.error.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default {@link ConversationService}: every read and write is scoped to {tenant, owner}. */
@Service
public class ConversationServiceImpl implements ConversationService {

  private static final int LIST_CAP = 200;

  private final ConversationRepository repository;

  public ConversationServiceImpl(ConversationRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public ConversationResponse create(
      UUID schoolId, UUID ownerUserId, CreateConversationRequest request) {
    String title = request == null || request.title() == null ? null : request.title().trim();
    Conversation saved =
        repository.save(
            new Conversation(
                schoolId, ownerUserId, title == null || title.isEmpty() ? null : title));
    return ConversationResponse.from(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ConversationResponse> listForOwner(UUID schoolId, UUID ownerUserId) {
    return repository.findByOwner(ownerUserId, PageRequest.of(0, LIST_CAP)).stream()
        .map(ConversationResponse::from)
        .toList();
  }

  @Override
  @Transactional
  public void delete(UUID schoolId, UUID ownerUserId, UUID conversationId) {
    Conversation conversation = requireOwned(schoolId, ownerUserId, conversationId);
    repository.delete(conversation);
  }

  @Override
  @Transactional(readOnly = true)
  public Conversation requireOwned(UUID schoolId, UUID ownerUserId, UUID conversationId) {
    Conversation conversation =
        repository.findById(conversationId).orElseThrow(NotFoundException::new);
    if (!conversation.getSchoolId().equals(schoolId)
        || !conversation.getOwnerUserId().equals(ownerUserId)) {
      // Anti-enumeration: a foreign or cross-tenant conversation looks identical to a missing one.
      throw new NotFoundException();
    }
    return conversation;
  }
}

