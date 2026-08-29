package com.schoolbridge.api.identity.device;

import com.schoolbridge.api.common.error.NotFoundException;
import com.schoolbridge.api.identity.device.dto.DeviceTokenResponse;
import com.schoolbridge.api.identity.device.dto.RegisterDeviceRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceTokenService {

  private final DeviceTokenRepository repository;

  public DeviceTokenService(DeviceTokenRepository repository) {
    this.repository = repository;
  }

  /**
   * Upserts a device token: creates a new row or refreshes the FCM token if the (userId, deviceId)
   * pair already exists. Runs within the current tenant context.
   */
  @Transactional
  public DeviceTokenResponse register(UUID schoolId, UUID userId, RegisterDeviceRequest request) {
    DeviceToken token =
        repository
            .findByUserIdAndDeviceId(userId, request.deviceId())
            .map(
                existing -> {
                  existing.refresh(request.fcmToken());
                  return existing;
                })
            .orElseGet(
                () ->
                    repository.save(
                        new DeviceToken(
                            schoolId,
                            userId,
                            request.platform(),
                            request.fcmToken(),
                            request.deviceId())));
    return DeviceTokenResponse.from(token);
  }

  /** Soft-deactivates the device token so it no longer receives pushes. */
  @Transactional
  public void deregister(UUID userId, String deviceId) {
    DeviceToken token =
        repository
            .findByUserIdAndDeviceId(userId, deviceId)
            .orElseThrow(() -> new NotFoundException("error.device.not_found"));
    token.deactivate();
  }
}

