package com.schoolbridge.api.grades;

import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.common.web.PageResponse;
import com.schoolbridge.api.grades.dto.CreateGradeRequest;
import com.schoolbridge.api.grades.dto.GradeRecordResponse;
import com.schoolbridge.api.grades.dto.UpdateGradeRequest;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiConstants.API_V1 + "/grades")
@Tag(name = "Grades", description = "Grade records management and parent read access")
public class GradesController {

  private final GradeService service;

  public GradesController(GradeService service) {
    this.service = service;
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','SCHOOL_ADMIN','TEACHER')")
  @PreAuthorize("hasRole('SCHOOL_ADMIN') or @perms.teacherTeaches(#request.classId())")
  @Operation(summary = "Create grade record")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Grade record created"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Student or class not found"),
    @ApiResponse(responseCode = "409", description = "Grade already exists for this combination"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<GradeRecordResponse> create(
      @Valid @RequestBody CreateGradeRequest request, Authentication authentication) {
    UUID schoolId = TenantContext.require();
    UUID actorId = requireStaff(authentication).userId();
    GradeRecordResponse created = service.create(schoolId, actorId, request);
    return ResponseEntity.created(URI.create(ApiConstants.API_V1 + "/grades/" + created.id()))
        .body(created);
  }

  @GetMapping(params = "studentId")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','SCHOOL_ADMIN','TEACHER','PARENT')")
  @PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'TEACHER') or @perms.parentLinkedTo(#studentId)")
  @Operation(
      summary = "List grades for a student",
      description =
          "Returns all grade records for the given student. "
              + "Parents receive 403 if they are not linked to the student (anti-enumeration).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Grade list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
  })
  public ResponseEntity<List<GradeRecordResponse>> listByStudent(@RequestParam UUID studentId) {
    return ResponseEntity.ok(service.listByStudent(studentId));
  }

  @GetMapping(params = "classId")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','SCHOOL_ADMIN','TEACHER','PARENT')")
  @PreAuthorize("hasRole('SCHOOL_ADMIN') or @perms.teacherTeaches(#classId)")
  @Operation(summary = "List grades for a class")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated grade list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
  })
  public ResponseEntity<PageResponse<GradeRecordResponse>> listByClass(
      @RequestParam UUID classId,
      @PageableDefault(size = 20, sort = "period", direction = Sort.Direction.ASC)
          Pageable pageable) {
    return ResponseEntity.ok(PageResponse.from(service.listByClass(classId, pageable)));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','SCHOOL_ADMIN','TEACHER','PARENT')")
  @Operation(summary = "Get a single grade record")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Grade record found"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "404", description = "Grade record not found")
  })
  public ResponseEntity<GradeRecordResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(service.findById(id));
  }

  @PatchMapping("/{id}")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','SCHOOL_ADMIN','TEACHER')")
  @Operation(summary = "Update a grade record")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Updated"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Grade record not found"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<GradeRecordResponse> update(
      @PathVariable UUID id,
      @Valid @RequestBody UpdateGradeRequest request,
      Authentication authentication) {
    UUID actorId = requireStaff(authentication).userId();
    return ResponseEntity.ok(service.update(id, actorId, request));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','SCHOOL_ADMIN','TEACHER')")
  @Operation(summary = "Delete a grade record")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Deleted"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Grade record not found")
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

