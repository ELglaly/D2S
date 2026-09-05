package com.schoolbridge.api.identity;

import com.schoolbridge.api.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Opaque refresh token. The raw token is returned once to the client; only the SHA-256 hex hash is
 * stored. Rotation marks the old row revoked and links it to the new hash for audit/replay
 * detection.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

  @Enumerated(EnumType.STRING)
  @Column(name = "subject_kind", nullable = false, length = 20)
  private SubjectKind subjectKind;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "replaced_by_token_hash", length = 64)
  private String replacedByTokenHash;

  protected RefreshToken() {}

  public RefreshToken(
      SubjectKind subjectKind, UUID subjectId, String tokenHash, Instant expiresAt) {
    this.subjectKind = subjectKind;
    this.subjectId = subjectId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }

  public void revoke(String replacedByHash) {
    this.revokedAt = Instant.now();
    this.replacedByTokenHash = replacedByHash;
  }

  public boolean isActive(Instant now) {
    return revokedAt == null && now.isBefore(expiresAt);
  }

  public SubjectKind getSubjectKind() {
    return subjectKind;
  }

  public UUID getSubjectId() {
    return subjectId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }
}
