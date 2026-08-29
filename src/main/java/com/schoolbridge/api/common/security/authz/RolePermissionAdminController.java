package com.schoolbridge.api.common.security.authz;

import com.schoolbridge.api.common.security.authz.dto.PermissionResponse;
import com.schoolbridge.api.common.security.authz.dto.RolePermissionsResponse;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.identity.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manages roleâ†’permission mappings at runtime. Secured by {@link RequirePermission}: {@code
 * MANAGE_PERMISSIONS} is required to read the catalog and to grant/revoke; {@code MANAGE_ROLES} to
 * inspect a role's grants. Both are seeded to SUPER_ADMIN only, so no other role can alter authz
 * mappings â€” the aspect denies (403) before any handler body runs.
 */
@RestController
@RequestMapping(ApiConstants.API_V1 + "/admin/authz")
@Tag(name = "Authorization Admin", description = "Manage roleâ†’permission mappings")
public class RolePermissionAdminController {

  private final RolePermissionAdminService service;

  public RolePermissionAdminController(RolePermissionAdminService service) {
    this.service = service;
  }

  @GetMapping("/permissions")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  @Operation(summary = "List the permission catalog")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Permission catalog"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires MANAGE_PERMISSIONS")
  })
  public List<PermissionResponse> catalog() {
    return service.catalog().stream()
        .map(p -> new PermissionResponse(p.getName(), p.getDescription()))
        .toList();
  }

  @GetMapping("/roles/{role}/permissions")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  @Operation(summary = "List the permissions granted to a role")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Granted permissions"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires MANAGE_ROLES")
  })
  public RolePermissionsResponse list(@PathVariable UserRole role) {
    return new RolePermissionsResponse(role.name(), service.permissionsOf(role));
  }

  @PostMapping("/roles/{role}/permissions/{permission}")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  @Operation(summary = "Grant a permission to a role")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Granted (idempotent)"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires MANAGE_PERMISSIONS")
  })
  public ResponseEntity<Void> grant(
      @PathVariable UserRole role, @PathVariable Permission permission) {
    service.grant(role, permission);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/roles/{role}/permissions/{permission}")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  @Operation(summary = "Revoke a permission from a role")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Revoked (idempotent)"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires MANAGE_PERMISSIONS")
  })
  public ResponseEntity<Void> revoke(
      @PathVariable UserRole role, @PathVariable Permission permission) {
    service.revoke(role, permission);
    return ResponseEntity.noContent().build();
  }
}

