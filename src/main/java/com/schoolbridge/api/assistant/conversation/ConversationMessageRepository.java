package com.schoolbridge.api.assistant.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link ConversationMessage}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL because Hibernate's {@code @Filter} does
 * NOT apply to {@code EntityManager.find()} (direct primary-key lookups). See {@code
 * UserRepository} for the canonical explanation.
 */
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {

  @Override
  @Query("select m from ConversationMessage m where m.id = :id")
  Optional<ConversationMessage> findById(@Param("id") UUID id);

  /**
   * The most recent messages in a conversation, newest first. The caller reverses to chronological
   * order before building the prompt. Pass {@code PageRequest.of(0, maxHistory)}.
   */
  @Query(
      "select m from ConversationMessage m where m.conversationId = :conversationId "
          + "order by m.createdAt desc")
  List<ConversationMessage> findRecent(
      @Param("conversationId") UUID conversationId, Pageable pageable);

  /** The single newest assistant turn â€” used to find a pending confirmation. */
  @Query(
      "select m from ConversationMessage m where m.conversationId = :conversationId "
          + "and m.role = com.schoolbridge.api.assistant.conversation.MessageRole.ASSISTANT "
          + "order by m.createdAt desc")
  List<ConversationMessage> findLatestAssistant(
      @Param("conversationId") UUID conversationId, Pageable pageable);
}
