package com.schoolbridge.api.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** Append-only record of a high-impact action, retained for compliance/audit (FR-7.5). */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.TIME)
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "school_id", nullable = false, updatable = false)
  private UUID schoolId;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(nullable = false, length = 100)
  private String action;

  @Column(name = "entity_type", length = 100)
  private String entityType;

  @Column(name = "entity_id")
  private UUID entityId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String metadata;

  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AuditLog() {}

  public AuditLog(
      UUID schoolId,
      UUID actorUserId,
      String action,
      String entityType,
      UUID entityId,
      String metadata,
      String ipAddress) {
    this.schoolId = schoolId;
    this.actorUserId = actorUserId;
    this.action = action;
    this.entityType = entityType;
    this.entityId = entityId;
    this.metadata = metadata;
    this.ipAddress = ipAddress;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSchoolId() {
    return schoolId;
  }

  public String getAction() {
    return action;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

