package com.schoolbridge.api.subjects;

import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.common.security.authz.RequirePermission;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.common.web.PageResponse;
import com.schoolbridge.api.subjects.dto.CreateSubjectRequest;
import com.schoolbridge.api.subjects.dto.SubjectResponse;
import com.schoolbridge.api.subjects.dto.UpdateSubjectRequest;
import com.schoolbridge.api.subjects.service.SubjectService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiConstants.API_V1 + "/subjects")
@Tag(name = "Subjects", description = "Subject catalog management")
public class SubjectController {

  private final SubjectService service;

  public SubjectController(SubjectService service) {
    this.service = service;
  }

  @PostMapping
  @RequirePermission(Permission.SUBJECT_MANAGE)
  @Operation(summary = "Create a subject")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Subject created"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "409", description = "Subject name already exists in this school"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<SubjectResponse> create(@Valid @RequestBody CreateSubjectRequest request) {
    UUID schoolId = TenantContext.require();
    SubjectResponse created = service.create(schoolId, request);
    return ResponseEntity.created(URI.create(ApiConstants.API_V1 + "/subjects/" + created.id()))
        .body(created);
  }

  @GetMapping
  @RequirePermission(Permission.SUBJECT_READ)
  @Operation(summary = "List all subjects")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated subject list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated")
  })
  public ResponseEntity<PageResponse<SubjectResponse>> list(
      @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
          Pageable pageable) {
    UUID schoolId = TenantContext.require();
    return ResponseEntity.ok(PageResponse.from(service.list(schoolId, pageable)));
  }

  @GetMapping("/{id}")
  @RequirePermission(Permission.SUBJECT_READ)
  @Operation(summary = "Get a subject by ID")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Subject found"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "404", description = "Subject not found")
  })
  public ResponseEntity<SubjectResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(service.findById(id));
  }

  @PatchMapping("/{id}")
  @RequirePermission(Permission.SUBJECT_MANAGE)
  @Operation(summary = "Update a subject")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Updated"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Subject not found"),
    @ApiResponse(responseCode = "409", description = "Subject name already exists in this school"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<SubjectResponse> update(
      @PathVariable UUID id, @Valid @RequestBody UpdateSubjectRequest request) {
    return ResponseEntity.ok(service.update(id, request));
  }

  @DeleteMapping("/{id}")
  @RequirePermission(Permission.SUBJECT_MANAGE)
  @Operation(summary = "Delete a subject")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Deleted"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Subject not found")
  })
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
