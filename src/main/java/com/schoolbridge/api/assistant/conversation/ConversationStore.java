package com.schoolbridge.api.assistant.conversation;

import com.schoolbridge.api.assistant.llm.LlmContent;
import com.schoolbridge.api.assistant.llm.LlmMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short, committed transactions around conversation message persistence. Kept as its own bean so
 * the long-lived streaming method in {@code ConversationChatService} (which is NOT transactional)
 * still goes through the {@code @Transactional} proxy â€” a self-invocation would silently bypass it.
 */
@Service
public class ConversationStore {

  private final ConversationRepository conversations;
  private final ConversationMessageRepository messages;

  public ConversationStore(
      ConversationRepository conversations, ConversationMessageRepository messages) {
    this.conversations = conversations;
    this.messages = messages;
  }

  /** Persists a user turn immediately and bumps the conversation's activity timestamp. */
  @Transactional
  public void appendUser(UUID schoolId, UUID conversationId, String content) {
    messages.save(ConversationMessage.user(schoolId, conversationId, content));
    touch(conversationId);
  }

  /**
   * Persists a completed assistant turn (only called after a stream finishes successfully). {@code
   * pendingActionToken} is non-null when the turn proposed a mutation awaiting CONFIRM/CANCEL.
   */
  @Transactional
  public void appendAssistant(
      UUID schoolId,
      UUID conversationId,
      String content,
      String pendingActionToken,
      Long inputTokens,
      Long outputTokens) {
    messages.save(
        ConversationMessage.assistant(schoolId, conversationId, content)
            .withPendingAction(pendingActionToken)
            .withUsage(inputTokens, outputTokens));
    touch(conversationId);
  }

  /** The recent window of turns, oldest-first, ready to replay to the model. */
  @Transactional(readOnly = true)
  public List<LlmMessage> loadHistory(UUID conversationId, int maxMessages) {
    List<ConversationMessage> recent =
        messages.findRecent(conversationId, PageRequest.of(0, Math.max(1, maxMessages)));
    List<LlmMessage> history = new ArrayList<>(recent.size());
    for (int i = recent.size() - 1; i >= 0; i--) {
      ConversationMessage m = recent.get(i);
      history.add(
          m.getRole() == MessageRole.USER
              ? LlmMessage.user(m.getContent())
              : LlmMessage.assistant(List.of(new LlmContent.Text(m.getContent()))));
    }
    return history;
  }

  /** The pending-action token on the latest assistant turn, if it is awaiting a CONFIRM/CANCEL. */
  @Transactional(readOnly = true)
  public Optional<String> latestPendingToken(UUID conversationId) {
    return messages.findLatestAssistant(conversationId, PageRequest.of(0, 1)).stream()
        .findFirst()
        .map(ConversationMessage::getPendingActionToken)
        .filter(token -> token != null && !token.isBlank());
  }

  private void touch(UUID conversationId) {
    conversations.findById(conversationId).ifPresent(c -> c.recordActivity(Instant.now()));
  }
}

