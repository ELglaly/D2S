package com.schoolbridge.api.identity.auth;

import com.schoolbridge.api.identity.auth.dto.ParentLogoutRequest;
import com.schoolbridge.api.identity.auth.dto.RequestOtpRequest;
import com.schoolbridge.api.identity.auth.dto.RequestOtpResponse;
import com.schoolbridge.api.identity.auth.dto.VerifyOtpRequest;
import com.schoolbridge.api.identity.auth.dto.VerifyOtpResponse;
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
@RequestMapping("/api/v1/parents/auth")
@Tag(name = "Parent Auth", description = "Phone-based OTP authentication for parents")
public class ParentAuthController {

  private final ParentAuthService service;

  public ParentAuthController(ParentAuthService service) {
    this.service = service;
  }

  @PostMapping("/request-otp")
  @SecurityRequirements
  @Operation(
      summary = "Request OTP",
      description =
          "Sends a 6-digit OTP via WhatsApp to the registered phone number. "
              + "Returns a ticket ID regardless of whether the phone is known (anti-enumeration). "
              + "Rate-limited per phone number.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OTP sent (or silently dropped â€” anti-enumeration)"),
    @ApiResponse(responseCode = "422", description = "Validation error (malformed phone number)"),
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for this phone number")
  })
  public ResponseEntity<RequestOtpResponse> requestOtp(
      @Valid @RequestBody RequestOtpRequest request) {
    return ResponseEntity.ok(service.requestOtp(request));
  }

  @PostMapping("/verify-otp")
  @SecurityRequirements
  @Operation(
      summary = "Verify OTP and obtain parent token",
      description =
          "Exchanges a valid ticket + 6-digit code for a 24-hour sliding-TTL bearer token. "
              + "Include this token in the Authorization header for all subsequent parent requests.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OTP verified; parent token returned"),
    @ApiResponse(responseCode = "400", description = "Invalid or expired ticket / wrong code"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<VerifyOtpResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
    return ResponseEntity.ok(service.verifyOtp(request));
  }

  @PostMapping("/logout")
  @SecurityRequirements
  @Operation(
      summary = "Logout parent",
      description = "Revokes the parent session token. Idempotent.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Logged out (or token was already invalid)"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<Void> logout(@Valid @RequestBody ParentLogoutRequest request) {
    service.logout(request.token());
    return ResponseEntity.noContent().build();
  }
}

