package com.schoolbridge.api.common.security.authz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Global permission catalog row. NOT a {@code TenantEntity}: permission names are platform-wide, so
 * there is no Hibernate tenant filter and no {@code findById} override to worry about.
 */
@Entity
@Table(name = "permissions")
public class PermissionEntity {

  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 64)
  private String name;

  @Column(length = 255)
  private String description;

  protected PermissionEntity() {}

  public PermissionEntity(UUID id, String name, String description) {
    this.id = id;
    this.name = name;
    this.description = description;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }
}

