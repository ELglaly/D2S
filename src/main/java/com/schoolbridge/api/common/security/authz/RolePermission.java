package com.schoolbridge.api.common.security.authz;

import com.schoolbridge.api.identity.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

/**
 * Join row mapping one {@link UserRole} to one {@link PermissionEntity}. The pair is unique. Global
 * (non-tenant): the same role→permission map applies to every school.
 */
@Entity
@Table(
    name = "role_permissions",
    uniqueConstraints = @UniqueConstraint(columnNames = {"role", "permission_id"}))
public class RolePermission {

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UserRole role;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "permission_id", nullable = false)
  private PermissionEntity permission;

  protected RolePermission() {}

  public RolePermission(UserRole role, PermissionEntity permission) {
    this.id = UUID.randomUUID();
    this.role = role;
    this.permission = permission;
  }

  public UUID getId() {
    return id;
  }

  public UserRole getRole() {
    return role;
  }

  public PermissionEntity getPermission() {
    return permission;
  }
}
