package com.schoolbridge.api.subjects;

import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.common.security.authz.RequirePermission;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.subjects.dto.AssignTeacherToSubjectRequest;
import com.schoolbridge.api.subjects.dto.StudentSubjectResponse;
import com.schoolbridge.api.subjects.dto.TeacherSubjectAssignmentResponse;
import com.schoolbridge.api.subjects.service.TeacherSubjectAssignmentService;
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
@Tag(
    name = "Teacher Subject Assignments",
    description = "Assign teachers to subjects within a class")
public class TeacherSubjectAssignmentController {

  private final TeacherSubjectAssignmentService service;

  public TeacherSubjectAssignmentController(TeacherSubjectAssignmentService service) {
    this.service = service;
  }

  @PostMapping("/classes/{classId}/subjects/{subjectId}/teachers")
  @RequirePermission(Permission.SUBJECT_MANAGE)
  @Operation(summary = "Assign a teacher to a subject in a class")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Teacher assigned"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Class, subject, or teacher not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Teacher already assigned to this subject in this class"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<TeacherSubjectAssignmentResponse> assign(
      @PathVariable UUID classId,
      @PathVariable UUID subjectId,
      @Valid @RequestBody AssignTeacherToSubjectRequest request) {
    TeacherSubjectAssignmentResponse created = service.assign(classId, subjectId, request);
    return ResponseEntity.created(
            URI.create(ApiConstants.API_V1 + "/teacher-subject-assignments/" + created.id()))
        .body(created);
  }

  @GetMapping("/classes/{classId}/subjects/{subjectId}/teachers")
  @RequirePermission(Permission.SUBJECT_MANAGE)
  @Operation(summary = "List teachers assigned to a subject in a class")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Assignment list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
  })
  public ResponseEntity<List<TeacherSubjectAssignmentResponse>> listByClassAndSubject(
      @PathVariable UUID classId, @PathVariable UUID subjectId) {
    return ResponseEntity.ok(service.listByClassAndSubject(classId, subjectId));
  }

  @GetMapping("/classes/{classId}/teacher-subject-assignments")
  @RequirePermission(Permission.SUBJECT_MANAGE)
  @Operation(summary = "List all teacher-subject assignments for a class")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Assignment list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
  })
  public ResponseEntity<List<TeacherSubjectAssignmentResponse>> listByClass(
      @PathVariable UUID classId) {
    return ResponseEntity.ok(service.listByClass(classId));
  }

  @DeleteMapping("/teacher-subject-assignments/{id}")
  @RequirePermission(Permission.SUBJECT_MANAGE)
  @Operation(summary = "Remove a teacher-subject assignment")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Removed"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Assignment not found")
  })
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/students/{studentId}/subjects")
  @RequirePermission(Permission.SUBJECT_READ)
  @Operation(
      summary = "Get subjects available to a student",
      description =
          "Returns all subjects from classes the student is enrolled in, with assigned teacher.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Subject list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
  })
  public ResponseEntity<List<StudentSubjectResponse>> getSubjectsForStudent(
      @PathVariable UUID studentId) {
    return ResponseEntity.ok(service.getSubjectsForStudent(studentId));
  }
}
