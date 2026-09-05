package com.schoolbridge.api.classes;

import com.schoolbridge.api.classes.dto.EnrollStudentRequest;
import com.schoolbridge.api.classes.dto.EnrollmentResponse;
import com.schoolbridge.api.classes.service.EnrollmentService;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.common.security.authz.RequirePermission;
import com.schoolbridge.api.common.web.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
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
@Tag(name = "Enrollments", description = "Student-to-class enrollment management")
public class EnrollmentController {

  private final EnrollmentService service;

  public EnrollmentController(EnrollmentService service) {
    this.service = service;
  }

  @PostMapping("/classes/{classId}/enrollments")
  @RequirePermission(Permission.ENROLLMENT_MANAGE)
  @Operation(
      summary = "Enroll student in class",
      description = "Creates an enrollment record linking a student to a class.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Student enrolled"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Class or student not found"),
    @ApiResponse(responseCode = "409", description = "Student is already enrolled in this class"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<EnrollmentResponse> enroll(
      @PathVariable UUID classId, @Valid @RequestBody EnrollStudentRequest request) {
    EnrollmentResponse created = service.enroll(classId, request);
    return ResponseEntity.created(URI.create(ApiConstants.API_V1 + "/enrollments/" + created.id()))
        .body(created);
  }

  @GetMapping("/classes/{classId}/enrollments")
  @RequirePermission(Permission.ENROLLMENT_MANAGE)
  @Operation(
      summary = "List enrollments for a class",
      description =
          "Returns all active student enrollments for the class. "
              + "SCHOOL_ADMIN sees all classes; TEACHER sees only their assigned classes.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Enrollment list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(
        responseCode = "403",
        description = "Requires SCHOOL_ADMIN or teacher assigned to the class"),
    @ApiResponse(responseCode = "404", description = "Class not found")
  })
  public ResponseEntity<List<EnrollmentResponse>> listByClass(@PathVariable UUID classId) {
    return ResponseEntity.ok(service.listByClass(classId));
  }

  @DeleteMapping("/enrollments/{id}")
  @RequirePermission(Permission.ENROLLMENT_MANAGE)
  @Operation(
      summary = "Remove enrollment",
      description = "Removes a student from a class by deleting the enrollment record.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Enrollment removed"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Enrollment not found")
  })
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
