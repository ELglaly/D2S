package com.schoolbridge.api.identity;

import com.schoolbridge.api.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** Platform-level super-admin account. Not tenant-scoped. */
@Entity
@Table(name = "platform_admins")
public class PlatformAdmin extends BaseEntity {

  @Column(nullable = false, length = 255, unique = true)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Column(nullable = false, length = 255)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UserStatus status;

  protected PlatformAdmin() {}

  public PlatformAdmin(String email, String passwordHash, String name) {
    this.email = email;
    this.passwordHash = passwordHash;
    this.name = name;
    this.status = UserStatus.ACTIVE;
  }

  public void replacePasswordHash(String newHash) {
    this.passwordHash = newHash;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getName() {
    return name;
  }

  public UserStatus getStatus() {
    return status;
  }
}
