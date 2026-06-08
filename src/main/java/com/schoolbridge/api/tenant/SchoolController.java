package com.schoolbridge.api.tenant;

import com.schoolbridge.api.common.web.PageResponse;
import com.schoolbridge.api.tenant.dto.CreateSchoolRequest;
import com.schoolbridge.api.tenant.dto.SchoolResponse;
import com.schoolbridge.api.tenant.dto.SchoolSettingsRequest;
import com.schoolbridge.api.tenant.dto.SchoolSettingsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schools")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(
    name = "Schools",
    description = "Platform-admin school (tenant) management. All endpoints require SUPER_ADMIN.")
public class SchoolController {

  private final SchoolService service;

  public SchoolController(SchoolService service) {
    this.service = service;
  }

  @PostMapping
  @Operation(
      summary = "Create school (tenant)",
      description =
          "Provisions a new school tenant on the platform. The school is created in ACTIVE status.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "School created"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SUPER_ADMIN"),
    @ApiResponse(responseCode = "409", description = "A school with the same slug already exists"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<SchoolResponse> create(@Valid @RequestBody CreateSchoolRequest request) {
    SchoolResponse created = service.create(request);
    return ResponseEntity.created(URI.create("/api/v1/schools/" + created.id())).body(created);
  }

  @GetMapping
  @Operation(
      summary = "List schools",
      description = "Returns a paginated list of all schools on the platform, newest first.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated school list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SUPER_ADMIN")
  })
  public ResponseEntity<PageResponse<SchoolResponse>> list(
      @Parameter(description = "Filter by school status; omit to return all")
          @RequestParam(required = false)
          SchoolStatus status,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return ResponseEntity.ok(PageResponse.from(service.list(status, pageable)));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get school", description = "Returns a single school by ID.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "School found"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SUPER_ADMIN"),
    @ApiResponse(responseCode = "404", description = "School not found")
  })
  public ResponseEntity<SchoolResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(service.findById(id));
  }

  @GetMapping("/{id}/settings")
  @Operation(
      summary = "Get school settings",
      description = "Returns configurable settings for a school (timezone, quiet hours, etc.).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Settings found"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SUPER_ADMIN"),
    @ApiResponse(responseCode = "404", description = "School not found")
  })
  public ResponseEntity<SchoolSettingsResponse> getSettings(@PathVariable UUID id) {
    return ResponseEntity.ok(service.getSettings(id));
  }

  @PutMapping("/{id}/settings")
  @Operation(
      summary = "Update school settings",
      description = "Replaces the school's settings. All fields must be supplied.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Settings updated"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SUPER_ADMIN"),
    @ApiResponse(responseCode = "404", description = "School not found"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<SchoolSettingsResponse> updateSettings(
      @PathVariable UUID id, @Valid @RequestBody SchoolSettingsRequest request) {
    return ResponseEntity.ok(service.updateSettings(id, request));
  }

  @PostMapping("/{id}/suspend")
  @Operation(
      summary = "Suspend school",
      description =
          "Marks a school as SUSPENDED. Suspended schools cannot authenticate new tokens.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "School suspended"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SUPER_ADMIN"),
    @ApiResponse(responseCode = "404", description = "School not found"),
    @ApiResponse(responseCode = "409", description = "School is already suspended")
  })
  public ResponseEntity<Void> suspend(@PathVariable UUID id) {
    service.suspend(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/reactivate")
  @Operation(
      summary = "Reactivate school",
      description = "Restores a SUSPENDED school to ACTIVE status.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "School reactivated"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SUPER_ADMIN"),
    @ApiResponse(responseCode = "404", description = "School not found"),
    @ApiResponse(responseCode = "409", description = "School is not in a suspended state")
  })
  public ResponseEntity<Void> reactivate(@PathVariable UUID id) {
    service.reactivate(id);
    return ResponseEntity.noContent().build();
  }
}
