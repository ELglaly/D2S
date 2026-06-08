package com.schoolbridge.api.identity.auth.dto;

import java.util.UUID;

public record VerifyOtpResponse(
    String token, UUID schoolId, String tokenType, long expiresInSeconds) {

  public static VerifyOtpResponse of(String token, UUID schoolId) {
    return new VerifyOtpResponse(token, schoolId, "Bearer", 86_400L);
  }
}
