package com.schoolbridge.api.assistant.conversation;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One turn in a {@link Conversation}. User turns are persisted immediately; assistant turns only
 * after their stream completes successfully. {@code pendingActionToken} is set on an assistant turn
 * that proposed a mutation and is awaiting a CONFIRM/CANCEL reply (it points at the single-use
 * action held in Redis).
 */
@Entity
@Table(name = "assistant_messages")
public class ConversationMessage extends TenantEntity {

  @Column(name = "conversation_id", nullable = false, updatable = false)
  private UUID conversationId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20, updatable = false)
  private MessageRole role;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  @Column(name = "pending_action_token", length = 255)
  private String pendingActionToken;

  @Column(name = "input_tokens")
  private Long inputTokens;

  @Column(name = "output_tokens")
  private Long outputTokens;

  protected ConversationMessage() {}

  private ConversationMessage(
      UUID schoolId, UUID conversationId, MessageRole role, String content) {
    super(schoolId);
    this.conversationId = conversationId;
    this.role = role;
    this.content = content;
  }

  public static ConversationMessage user(UUID schoolId, UUID conversationId, String content) {
    return new ConversationMessage(schoolId, conversationId, MessageRole.USER, content);
  }

  public static ConversationMessage assistant(UUID schoolId, UUID conversationId, String content) {
    return new ConversationMessage(schoolId, conversationId, MessageRole.ASSISTANT, content);
  }

  /** Marks this assistant turn as awaiting confirmation of the given pending-action token. */
  public ConversationMessage withPendingAction(String token) {
    this.pendingActionToken = token;
    return this;
  }

  public ConversationMessage withUsage(Long inputTokens, Long outputTokens) {
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
    return this;
  }

  public UUID getConversationId() {
    return conversationId;
  }

  public MessageRole getRole() {
    return role;
  }

  public String getContent() {
    return content;
  }

  public String getPendingActionToken() {
    return pendingActionToken;
  }

  public Long getInputTokens() {
    return inputTokens;
  }

  public Long getOutputTokens() {
    return outputTokens;
  }
}

