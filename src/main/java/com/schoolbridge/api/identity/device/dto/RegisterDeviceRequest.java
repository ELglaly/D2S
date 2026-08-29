package com.schoolbridge.api.identity.device.dto;

import com.schoolbridge.api.identity.device.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterDeviceRequest(
    @NotNull DevicePlatform platform,
    @NotBlank @Size(max = 256) String fcmToken,
    @NotBlank @Size(max = 128) String deviceId) {}

