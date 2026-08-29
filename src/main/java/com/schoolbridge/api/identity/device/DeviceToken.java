package com.schoolbridge.api.identity.device;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Push notification registration token for a user's mobile device. One row per (user, device) pair;
 * re-registering the same deviceId refreshes the fcmToken rather than inserting a duplicate.
 */
@Entity
@Table(name = "device_tokens")
public class DeviceToken extends TenantEntity {

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "platform", nullable = false, length = 10)
  private DevicePlatform platform;

  @Column(name = "fcm_token", nullable = false, length = 256)
  private String fcmToken;

  /** Stable client-side device identifier (e.g. Android {@code Installation.id()}). */
  @Column(name = "device_id", nullable = false, updatable = false, length = 128)
  private String deviceId;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  protected DeviceToken() {}

  public DeviceToken(
      UUID schoolId, UUID userId, DevicePlatform platform, String fcmToken, String deviceId) {
    super(schoolId);
    this.userId = userId;
    this.platform = platform;
    this.fcmToken = fcmToken;
    this.deviceId = deviceId;
    this.active = true;
  }

  /** Called when the same device re-registers with a rotated FCM token. */
  public void refresh(String newFcmToken) {
    this.fcmToken = newFcmToken;
    this.active = true;
  }

  public void deactivate() {
    this.active = false;
  }

  public UUID getUserId() {
    return userId;
  }

  public DevicePlatform getPlatform() {
    return platform;
  }

  public String getFcmToken() {
    return fcmToken;
  }

  public String getDeviceId() {
    return deviceId;
  }

  public boolean isActive() {
    return active;
  }
}

