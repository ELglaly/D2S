package com.schoolbridge.api.identity.device.dto;

import com.schoolbridge.api.identity.device.DevicePlatform;
import com.schoolbridge.api.identity.device.DeviceToken;
import java.util.UUID;

public record DeviceTokenResponse(
    UUID id, DevicePlatform platform, String deviceId, boolean active) {

  public static DeviceTokenResponse from(DeviceToken token) {
    return new DeviceTokenResponse(
        token.getId(), token.getPlatform(), token.getDeviceId(), token.isActive());
  }
}
