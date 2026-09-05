package com.schoolbridge.api.identity;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Tenant-scoped principal: a school admin, teacher, or parent. Sensitive columns are encrypted at
 * rest; {@code phoneHash} is a blind index so parent lookups by phone still work without a
 * deterministic ciphertext.
 */
@Entity
@Table(name = "users")
public class User extends TenantEntity {

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UserRole role;

  // @Convert(converter = AesGcmAttributeConverter.class)
  @Column(nullable = false, length = 1024)
  private String name;

  @Column(length = 255)
  private String email;

  // @Convert(converter = AesGcmAttributeConverter.class)
  @Column(length = 1024)
  private String phone;

  @Column(name = "phone_hash", length = 64)
  private String phoneHash;

  @Column(name = "password_hash", length = 255)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UserStatus status;

  protected User() {}

  private User(
      UUID schoolId,
      UserRole role,
      String name,
      String email,
      String phone,
      String phoneHash,
      String passwordHash) {
    super(schoolId);
    this.role = role;
    this.name = name;
    this.email = email;
    this.phone = phone;
    this.phoneHash = phoneHash;
    this.passwordHash = passwordHash;
    this.status = UserStatus.ACTIVE;
  }

  /** Staff factory â€” email + password required. */
  public static User staff(
      UUID schoolId, UserRole role, String name, String email, String passwordHash) {
    if (role == UserRole.PARENT) {
      throw new IllegalArgumentException("Use User.parent(...) for parent accounts");
    }
    return new User(schoolId, role, name, email, null, null, passwordHash);
  }

  /** Parent factory â€” phone + blind index required; no password. */
  public static User parent(UUID schoolId, String name, String phone, String phoneHash) {
    return new User(schoolId, UserRole.PARENT, name, null, phone, phoneHash, null);
  }

  public void suspend() {
    this.status = UserStatus.SUSPENDED;
  }

  public void reactivate() {
    this.status = UserStatus.ACTIVE;
  }

  public void replacePasswordHash(String newHash) {
    this.passwordHash = newHash;
  }

  public UserRole getRole() {
    return role;
  }

  public String getName() {
    return name;
  }

  public String getEmail() {
    return email;
  }

  public String getPhone() {
    return phone;
  }

  public String getPhoneHash() {
    return phoneHash;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public UserStatus getStatus() {
    return status;
  }
}
