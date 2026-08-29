package com.schoolbridge.api.assistant.conversation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link Conversation}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL because Hibernate's {@code @Filter} does
 * NOT apply to {@code EntityManager.find()} (direct primary-key lookups). See {@code
 * UserRepository} for the canonical explanation.
 */
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

  @Override
  @Query("select c from Conversation c where c.id = :id")
  Optional<Conversation> findById(@Param("id") UUID id);

  /** The owner's conversations, most-recently-active first. */
  @Query(
      "select c from Conversation c where c.ownerUserId = :ownerUserId "
          + "order by coalesce(c.lastMessageAt, c.createdAt) desc")
  Page<Conversation> findByOwner(@Param("ownerUserId") UUID ownerUserId, Pageable pageable);
}

