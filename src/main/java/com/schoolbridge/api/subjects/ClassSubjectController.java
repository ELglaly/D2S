package com.schoolbridge.api.subjects;

import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.common.security.authz.RequirePermission;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.common.web.PageResponse;
import com.schoolbridge.api.subjects.dto.AssignSubjectToClassRequest;
import com.schoolbridge.api.subjects.dto.ClassSubjectResponse;
import com.schoolbridge.api.subjects.service.ClassSubjectService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiConstants.API_V1)
@Tag(name = "Class Subjects", description = "Assign and manage subjects within a class")
public class ClassSubjectController {

  private final ClassSubjectService service;

  public ClassSubjectController(ClassSubjectService service) {
    this.service = service;
  }

  @PostMapping("/classes/{classId}/subjects")
  @RequirePermission(Permission.SUBJECT_MANAGE)
  @Operation(summary = "Assign a subject to a class")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Subject assigned to class"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Class or subject not found"),
    @ApiResponse(responseCode = "409", description = "Subject already assigned to this class"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<ClassSubjectResponse> assign(
      @PathVariable UUID classId, @Valid @RequestBody AssignSubjectToClassRequest request) {
    ClassSubjectResponse created = service.assign(classId, request);
    return ResponseEntity.created(
            URI.create(ApiConstants.API_V1 + "/class-subjects/" + created.id()))
        .body(created);
  }

  @GetMapping("/classes/{classId}/subjects")
  @RequirePermission(Permission.SUBJECT_READ)
  @Operation(summary = "List subjects in a class")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated list of class subjects"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
  })
  public ResponseEntity<PageResponse<ClassSubjectResponse>> listByClass(
      @PathVariable UUID classId,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC)
          Pageable pageable) {
    return ResponseEntity.ok(PageResponse.from(service.listByClass(classId, pageable)));
  }

  @DeleteMapping("/class-subjects/{id}")
  @RequirePermission(Permission.SUBJECT_MANAGE)
  @Operation(summary = "Remove a subject from a class")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Removed"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Class-subject assignment not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Cannot remove: teacher assignments reference this class-subject")
  })
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
