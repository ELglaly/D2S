package com.schoolbridge.api.classes;

import com.schoolbridge.api.classes.dto.BulkImportResult;
import com.schoolbridge.api.classes.dto.CreateStudentRequest;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.classes.dto.UpdateStudentRequest;
import com.schoolbridge.api.classes.service.StudentService;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.common.security.authz.RequirePermission;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(ApiConstants.API_V1 + "/students")
@Tag(name = "Students", description = "Student roster management and bulk import")
@RequirePermission(Permission.STUDENT_MANAGE)
public class StudentController {

  private final StudentService service;

  public StudentController(StudentService service) {
    this.service = service;
  }

  @PostMapping
  @Operation(
      summary = "Create student",
      description = "Creates a single student record for the school.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Student created"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<StudentResponse> create(@Valid @RequestBody CreateStudentRequest request) {
    UUID schoolId = TenantContext.require();
    StudentResponse created = service.create(schoolId, request);
    return ResponseEntity.created(URI.create(ApiConstants.API_V1 + "/students/" + created.id()))
        .body(created);
  }

  @GetMapping
  @Operation(
      summary = "List students",
      description = "Returns a paginated list of all students for the school, newest first.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated student list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN")
  })
  public ResponseEntity<PageResponse<StudentResponse>> list(
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    UUID schoolId = TenantContext.require();
    return ResponseEntity.ok(PageResponse.from(service.list(schoolId, pageable)));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get student", description = "Returns a single student by ID.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Student found"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Student not found")
  })
  public ResponseEntity<StudentResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(service.findById(id));
  }

  @PatchMapping("/{id}")
  @Operation(
      summary = "Update student",
      description = "Partially updates a student record. Only provided fields are changed.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Student updated"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Student not found"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<StudentResponse> update(
      @PathVariable UUID id, @Valid @RequestBody UpdateStudentRequest request) {
    return ResponseEntity.ok(service.update(id, request));
  }

  @DeleteMapping("/{id}")
  @Operation(
      summary = "Delete student",
      description = "Deletes a student. Enrollments and parent links are cascade-deleted.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Student deleted"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Student not found")
  })
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Accepts a UTF-8 CSV file with header {@code externalId,fullName,dateOfBirth,className} and
   * bulk-creates students, optionally enrolling them into a named class. Invalid rows are collected
   * and returned rather than aborting the whole import (FR-1.2 / NFR-U1).
   */
  @PostMapping(value = ":bulk-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(
      summary = "Bulk import students from CSV",
      description =
          "Accepts a UTF-8 CSV file with header: externalId,fullName,dateOfBirth,className. "
              + "Valid rows are created; invalid rows are collected and returned in the response body "
              + "rather than aborting the whole import. HTTP 200 is returned even when some rows fail.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Import completed; check body for per-row errors"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "422", description = "File missing or not a valid CSV")
  })
  public ResponseEntity<BulkImportResult> bulkImport(@RequestPart("file") MultipartFile file)
      throws IOException {
    UUID schoolId = TenantContext.require();
    BulkImportResult result = service.bulkImport(schoolId, file.getInputStream());
    return ResponseEntity.ok(result);
  }
}
