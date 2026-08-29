package com.schoolbridge.api.identity.device;

import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import com.schoolbridge.api.identity.device.dto.DeviceTokenResponse;
import com.schoolbridge.api.identity.device.dto.RegisterDeviceRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiConstants.API_V1 + "/devices")
@Tag(name = "Devices", description = "FCM push notification device token registration")
public class DeviceController {

  private final DeviceTokenService service;

  public DeviceController(DeviceTokenService service) {
    this.service = service;
  }

  @PostMapping("/register")
  @Operation(
      summary = "Register or refresh a device push token",
      description =
          "Upserts the FCM token for the authenticated user's device. "
              + "Call this on app startup and whenever the mobile SDK rotates the token.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Token registered or refreshed"),
    @ApiResponse(responseCode = "422", description = "Validation error"),
    @ApiResponse(responseCode = "401", description = "Not authenticated")
  })
  public ResponseEntity<DeviceTokenResponse> register(
      @Valid @RequestBody RegisterDeviceRequest request, Authentication authentication) {
    UUID schoolId = TenantContext.require();
    UUID userId = resolveUserId(authentication);
    return ResponseEntity.ok(service.register(schoolId, userId, request));
  }

  @DeleteMapping("/{deviceId}")
  @Operation(
      summary = "Deregister a device",
      description = "Marks the device token inactive so it no longer receives push notifications.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Device deregistered"),
    @ApiResponse(responseCode = "404", description = "Device not found for this user"),
    @ApiResponse(responseCode = "401", description = "Not authenticated")
  })
  public ResponseEntity<Void> deregister(
      @PathVariable String deviceId, Authentication authentication) {
    UUID userId = resolveUserId(authentication);
    service.deregister(userId, deviceId);
    return ResponseEntity.noContent().build();
  }

  private UUID resolveUserId(Authentication authentication) {
    if (authentication == null) {
      throw new TenantSecurityException();
    }
    return switch (authentication.getPrincipal()) {
      case StaffPrincipal staff -> staff.userId();
      case ParentPrincipal parent -> parent.userId();
      default -> throw new TenantSecurityException();
    };
  }
}

