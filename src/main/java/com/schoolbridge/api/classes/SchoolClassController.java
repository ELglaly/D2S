package com.schoolbridge.api.classes;

import com.schoolbridge.api.classes.dto.CreateSchoolClassRequest;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.dto.UpdateSchoolClassRequest;
import com.schoolbridge.api.classes.service.SchoolClassService;
import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.common.web.PageResponse;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApiConstants.API_V1 + "/classes")
@Tag(name = "Classes", description = "School class management")
public class SchoolClassController {

  private final SchoolClassService service;

  public SchoolClassController(SchoolClassService service) {
    this.service = service;
  }

  @PreAuthorize("hasAnyRole('SUPER_ADMIN','SCHOOL_ADMIN')")
  @PostMapping
  @Operation(summary = "Create class", description = "Creates a new school class for the tenant.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Class created"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<SchoolClassResponse> create(
      @Valid @RequestBody CreateSchoolClassRequest request) {
    UUID schoolId = TenantContext.require();
    SchoolClassResponse created = service.create(schoolId, request);
    return ResponseEntity.created(URI.create(ApiConstants.API_V1 + "/classes/" + created.id()))
        .body(created);
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','SCHOOL_ADMIN')")
  @Operation(
      summary = "List classes",
      description = "Returns a paginated list of all classes for the school, newest first.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated class list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN")
  })
  public ResponseEntity<PageResponse<SchoolClassResponse>> list(
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    UUID schoolId = TenantContext.require();
    return ResponseEntity.ok(PageResponse.from(service.list(schoolId, pageable)));
  }

  @GetMapping("/by-teacher")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','SCHOOL_ADMIN')")
  @Operation(
      summary = "List classes by teacher",
      description =
          "Returns a paginated list of classes where the given user is the homeroom teacher.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated class list"),
    @ApiResponse(responseCode = "400", description = "Missing or invalid teacherId"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN")
  })
  public ResponseEntity<PageResponse<SchoolClassResponse>> listByTeacher(
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable,
      @RequestParam UUID teacherId) {
    UUID schoolId = TenantContext.require();
    return ResponseEntity.ok(
        PageResponse.from(service.listByHomeroomTeacher(schoolId, teacherId, pageable)));
  }

  @GetMapping("/my-classes")
  @PreAuthorize("hasRole('TEACHER')")
  @Operation(
      summary = "List my classes",
      description =
          "Returns all classes assigned to the authenticated teacher â€” both as homeroom teacher "
              + "and via explicit TeacherAssignment records.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated class list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires TEACHER role")
  })
  public ResponseEntity<PageResponse<SchoolClassResponse>> myClasses(
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable,
      Authentication authentication) {
    UUID teacherUserId = requireStaff(authentication).userId();
    return ResponseEntity.ok(PageResponse.from(service.listMyClasses(teacherUserId, pageable)));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','SCHOOL_ADMIN')")
  @Operation(summary = "Get class", description = "Returns a single class by ID.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Class found"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Class not found")
  })
  public ResponseEntity<SchoolClassResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(service.findById(id));
  }

  @PreAuthorize("hasAnyRole('SUPER_ADMIN','SCHOOL_ADMIN')")
  @PatchMapping("/{id}")
  @Operation(
      summary = "Update class",
      description = "Partially updates a class. Only provided fields are changed.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Class updated"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Class not found"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<SchoolClassResponse> update(
      @PathVariable UUID id, @Valid @RequestBody UpdateSchoolClassRequest request) {
    return ResponseEntity.ok(service.update(id, request));
  }

  @PreAuthorize("hasAnyRole('SUPER_ADMIN','SCHOOL_ADMIN')")
  @DeleteMapping("/{id}")
  @Operation(
      summary = "Delete class",
      description = "Deletes a class. Enrollments and teacher assignments are cascade-deleted.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Class deleted"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Class not found")
  })
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }

  private StaffPrincipal requireStaff(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof StaffPrincipal staff)) {
      throw new TenantSecurityException();
    }
    return staff;
  }
}

