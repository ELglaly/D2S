package com.schoolbridge.api.identity.auth;

import com.schoolbridge.api.identity.auth.dto.AuthResponse;
import com.schoolbridge.api.identity.auth.dto.LoginRequest;
import com.schoolbridge.api.identity.auth.dto.LogoutRequest;
import com.schoolbridge.api.identity.auth.dto.RefreshRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(
    name = "Auth",
    description = "Staff and platform-admin authentication (email/password + JWT refresh)")
public class AuthController {

  private final AuthService service;

  public AuthController(AuthService service) {
    this.service = service;
  }

  @PostMapping("/login")
  @SecurityRequirements
  @Operation(
      summary = "Login with email and password",
      description = "Returns a short-lived JWT access token and a 30-day refresh token.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Login successful; tokens returned"),
    @ApiResponse(responseCode = "401", description = "Invalid email or password"),
    @ApiResponse(responseCode = "422", description = "Validation error (missing fields)")
  })
  public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    return ResponseEntity.ok(service.login(request));
  }

  @PostMapping("/refresh")
  @SecurityRequirements
  @Operation(
      summary = "Refresh access token",
      description =
          "Rotates the refresh token and returns a new access token. "
              + "The old refresh token is immediately revoked (single-use).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "New tokens returned"),
    @ApiResponse(responseCode = "401", description = "Refresh token invalid or expired"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
    return ResponseEntity.ok(service.refresh(request));
  }

  @PostMapping("/logout")
  @SecurityRequirements
  @Operation(
      summary = "Logout",
      description = "Revokes the refresh token. Idempotent â€” unknown tokens succeed silently.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Logged out (or token was already invalid)"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
    service.logout(request);
    return ResponseEntity.noContent().build();
  }
}

