package com.schoolbridge.api.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes immutable audit entries for high-impact actions (user created, fee changed, etc.). */
@Service
public class AuditService {

  private final AuditLogRepository repository;
  private final ObjectMapper objectMapper;

  public AuditService(AuditLogRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public void record(
      UUID schoolId,
      UUID actorUserId,
      String action,
      String entityType,
      UUID entityId,
      Map<String, Object> metadata) {
    repository.save(
        new AuditLog(
            schoolId, actorUserId, action, entityType, entityId, serialize(metadata), null));
  }

  private String serialize(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize audit metadata", ex);
    }
  }
}
