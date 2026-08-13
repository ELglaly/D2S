package com.schoolbridge.api.notifications;

import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import com.schoolbridge.api.notifications.dto.NotificationPreferencesRequest;
import com.schoolbridge.api.notifications.dto.NotificationPreferencesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service notification preferences for the authenticated user.
 *
 * <p>Carries no {@code @RequirePermission}, deliberately, and follows {@code DeviceController}: the
 * only row a caller can reach is their own, resolved from the principal rather than from a path
 * variable, so there is no cross-user surface for a permission to guard. A permission here would
 * suggest an administrator could edit someone else's consent, which is exactly what this must not
 * allow.
 */
@RestController
@RequestMapping(ApiConstants.API_V1 + "/notifications")
@Tag(name = "Notifications", description = "Per-user notification preferences and quiet hours")
public class NotificationPreferenceController {

  private final NotificationPreferenceService service;

  public NotificationPreferenceController(NotificationPreferenceService service) {
    this.service = service;
  }

  @GetMapping("/preferences")
  @Operation(
      summary = "Get the caller's notification preferences",
      description =
          "Returns every category, including ones the user has never configured — those come back "
              + "with their defaults, so the client never has to distinguish stored from default. "
              + "Attendance is always returned enabled: it cannot be switched off.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Preferences returned"),
    @ApiResponse(responseCode = "401", description = "Not authenticated")
  })
  public ResponseEntity<NotificationPreferencesResponse> get(Authentication authentication) {
    UUID schoolId = TenantContext.require();
    return ResponseEntity.ok(service.get(schoolId, resolveUserId(authentication)));
  }

  @PutMapping("/preferences")
  @Operation(
      summary = "Replace the caller's notification preferences",
      description =
          "Whole-set replace rather than a partial patch, so two devices editing at once cannot "
              + "interleave into a state the user never chose. Quiet hours must be supplied as a "
              + "pair or omitted entirely; omitting both inherits the school's window.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Preferences saved"),
    @ApiResponse(
        responseCode = "422",
        description = "Half a quiet-hours window, or an attempt to disable attendance alerts"),
    @ApiResponse(responseCode = "401", description = "Not authenticated")
  })
  public ResponseEntity<NotificationPreferencesResponse> replace(
      @Valid @RequestBody NotificationPreferencesRequest request, Authentication authentication) {
    UUID schoolId = TenantContext.require();
    return ResponseEntity.ok(service.replace(schoolId, resolveUserId(authentication), request));
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
