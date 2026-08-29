package com.schoolbridge.api.assistant.conversation;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A durable, multi-turn assistant conversation owned by exactly one user within a tenant. Messages
 * are stored separately ({@link ConversationMessage}); {@code lastMessageAt} bubbles active threads
 * to the top of the owner's list.
 */
@Entity
@Table(name = "assistant_conversations")
public class Conversation extends TenantEntity {

  @Column(name = "owner_user_id", nullable = false, updatable = false)
  private UUID ownerUserId;

  @Column(length = 200)
  private String title;

  @Column(name = "last_message_at")
  private Instant lastMessageAt;

  protected Conversation() {}

  public Conversation(UUID schoolId, UUID ownerUserId, String title) {
    super(schoolId);
    this.ownerUserId = ownerUserId;
    this.title = title;
  }

  /** Stamps the most recent activity so the owner's list can sort newest-first. */
  public void recordActivity(Instant when) {
    this.lastMessageAt = when;
  }

  public void rename(String title) {
    this.title = title;
  }

  public UUID getOwnerUserId() {
    return ownerUserId;
  }

  public String getTitle() {
    return title;
  }

  public Instant getLastMessageAt() {
    return lastMessageAt;
  }
}

