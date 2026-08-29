package com.schoolbridge.api.identity.auth.dto;

import java.time.Instant;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    Instant accessExpiresAt,
    String tokenType,
    String role) {

  public static AuthResponse bearer(
      String accessToken, String refreshToken, Instant accessExpiresAt, String role) {
    return new AuthResponse(accessToken, refreshToken, accessExpiresAt, "Bearer", role);
  }
}

