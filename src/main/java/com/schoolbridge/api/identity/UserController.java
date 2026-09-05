package com.schoolbridge.api.identity;

import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.common.security.authz.RequirePermission;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.common.web.PageResponse;
import com.schoolbridge.api.identity.dto.CreateUserRequest;
import com.schoolbridge.api.identity.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiConstants.API_V1 + "/schools/{schoolId}/users")
@RequirePermission(Permission.USER_MANAGE)
@Tag(
    name = "School Users",
    description = "Platform-admin user management within a school tenant. Requires SUPER_ADMIN.")
public class UserController {

  private final UserService service;

  public UserController(UserService service) {
    this.service = service;
  }

  @PostMapping
  @Operation(
      summary = "Create user",
      description =
          "Creates a staff member (SCHOOL_ADMIN / TEACHER) or parent in the given school. "
              + "Staff require email + password; parents require phone (OTP login, no password).")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "User created"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SUPER_ADMIN"),
    @ApiResponse(responseCode = "404", description = "School not found"),
    @ApiResponse(responseCode = "409", description = "Email or phone already in use"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<UserResponse> create(
      @PathVariable UUID schoolId, @Valid @RequestBody CreateUserRequest request) {
    UserResponse created = service.create(schoolId, request);
    return ResponseEntity.created(
            URI.create(ApiConstants.API_V1 + "/schools/" + schoolId + "/users/" + created.id()))
        .body(created);
  }

  @GetMapping
  @Operation(
      summary = "List users",
      description = "Returns a paginated list of all users in the school, newest first.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated user list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SUPER_ADMIN"),
    @ApiResponse(responseCode = "404", description = "School not found")
  })
  public ResponseEntity<PageResponse<UserResponse>> list(
      @PathVariable UUID schoolId,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return ResponseEntity.ok(PageResponse.from(service.list(schoolId, pageable)));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get user", description = "Returns a single user by ID within the school.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "User found"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SUPER_ADMIN"),
    @ApiResponse(responseCode = "404", description = "User not found")
  })
  public ResponseEntity<UserResponse> get(@PathVariable UUID schoolId, @PathVariable UUID id) {
    return ResponseEntity.ok(service.findById(schoolId, id));
  }
}
