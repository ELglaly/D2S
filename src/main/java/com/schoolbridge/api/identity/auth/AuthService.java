package com.schoolbridge.api.identity.auth;

import com.schoolbridge.api.identity.auth.dto.AuthResponse;
import com.schoolbridge.api.identity.auth.dto.LoginRequest;
import com.schoolbridge.api.identity.auth.dto.LogoutRequest;
import com.schoolbridge.api.identity.auth.dto.RefreshRequest;

public interface AuthService {

  /** Authenticates staff (User or PlatformAdmin) and issues a JWT access + opaque refresh pair. */
  AuthResponse login(LoginRequest request);

  /** Rotates the refresh token, returning a fresh access+refresh pair. */
  AuthResponse refresh(RefreshRequest request);

  /** Revokes a refresh token. Idempotent â€” unknown tokens silently succeed. */
  void logout(LogoutRequest request);
}

