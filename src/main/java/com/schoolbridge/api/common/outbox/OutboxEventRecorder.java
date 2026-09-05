package com.schoolbridge.api.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an outbox event inside the caller's existing transaction. Propagation is MANDATORY so an
 * event is never written without a surrounding domain transaction â€” that is what guarantees the
 * mutation and its event commit atomically.
 */
@Service
public class OutboxEventRecorder {

  private final OutboxRepository repository;
  private final ObjectMapper objectMapper;

  public OutboxEventRecorder(OutboxRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      UUID schoolId, String aggregateType, UUID aggregateId, String eventType, Object payload) {
    repository.save(
        new OutboxEvent(schoolId, aggregateType, aggregateId, eventType, serialize(payload)));
  }

  private String serialize(Object payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize outbox payload", ex);
    }
  }
}
