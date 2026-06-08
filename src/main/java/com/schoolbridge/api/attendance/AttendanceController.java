package com.schoolbridge.api.attendance;

import com.schoolbridge.api.attendance.dto.AttendanceHistoryEntry;
import com.schoolbridge.api.attendance.dto.AttendanceRecordResponse;
import com.schoolbridge.api.attendance.dto.AttendanceRosterEntry;
import com.schoolbridge.api.attendance.dto.MarkAllPresentRequest;
import com.schoolbridge.api.attendance.dto.MarkAllPresentResponse;
import com.schoolbridge.api.attendance.dto.MarkAttendanceRequest;
import com.schoolbridge.api.attendance.dto.ParentResponseRequest;
import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiConstants.API_V1 + "/attendance")
@Tag(name = "Attendance", description = "Daily attendance marking and parent-response flow")
public class AttendanceController {

  private final AttendanceService service;

  public AttendanceController(AttendanceService service) {
    this.service = service;
  }

  // Slash-style action verbs ({@code /mark}, {@code /mark-all-present}) instead of the
  // Google-AIP-style {@code :mark} the IMPLEMENTATION_PLAN proposes — REST clients (RestAssured,
  // OkHttp) URL-encode the colon to %3A, which makes Spring's path matcher route the request to
  // the static-resource fallback.
  @GetMapping("/{id}")
  @PreAuthorize("hasRole('SCHOOL_ADMIN') or hasRole('TEACHER')")
  @Operation(
      summary = "Get attendance record",
      description = "Returns a single attendance record by ID.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Attendance record found"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN or TEACHER"),
    @ApiResponse(responseCode = "404", description = "Record not found")
  })
  public ResponseEntity<AttendanceRecordResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(service.findById(id));
  }

  @PostMapping("/mark")
  @PreAuthorize(
      "hasRole('SCHOOL_ADMIN') or (hasRole('TEACHER') and @perms.teacherTeaches(#request.classId()))")
  @Operation(
      summary = "Mark attendance",
      description =
          "Records an attendance status (PRESENT, ABSENT, LATE, EXCUSED) for a single student "
              + "on the given date. SCHOOL_ADMIN may mark any class; TEACHER may only mark "
              + "classes they are assigned to.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Attendance recorded"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(
        responseCode = "403",
        description = "Requires SCHOOL_ADMIN or teacher assigned to the class"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<AttendanceRecordResponse> mark(
      @Valid @RequestBody MarkAttendanceRequest request, Authentication authentication) {
    UUID staffId = requireStaff(authentication).userId();
    return ResponseEntity.ok(service.mark(staffId, request));
  }

  @PostMapping("/mark-all-present")
  @PreAuthorize(
      "hasRole('SCHOOL_ADMIN') or (hasRole('TEACHER') and @perms.teacherTeaches(#request.classId()))")
  @Operation(
      summary = "Mark all students present",
      description =
          "Bulk marks every enrolled student in the class as PRESENT for the given date. "
              + "Students who already have an attendance record for the date are skipped.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Bulk mark completed; see body for counts"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(
        responseCode = "403",
        description = "Requires SCHOOL_ADMIN or teacher assigned to the class"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<MarkAllPresentResponse> markAllPresent(
      @Valid @RequestBody MarkAllPresentRequest request, Authentication authentication) {
    UUID staffId = requireStaff(authentication).userId();
    return ResponseEntity.ok(service.markAllPresent(staffId, request));
  }

  @GetMapping("/roster")
  @PreAuthorize(
      "hasRole('SCHOOL_ADMIN') or (hasRole('TEACHER') and @perms.teacherTeaches(#classId))")
  @Operation(
      summary = "Get attendance roster",
      description =
          "Returns the attendance entry for every enrolled student in a class on the given date. "
              + "Students with no record yet appear with status UNKNOWN.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Roster returned"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(
        responseCode = "403",
        description = "Requires SCHOOL_ADMIN or teacher assigned to the class"),
    @ApiResponse(responseCode = "404", description = "Class not found")
  })
  public ResponseEntity<List<AttendanceRosterEntry>> roster(
      @Parameter(description = "Class ID to fetch the roster for") @RequestParam UUID classId,
      @Parameter(description = "Date in ISO-8601 format (yyyy-MM-dd)")
          @RequestParam
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return ResponseEntity.ok(service.roster(classId, date));
  }

  @GetMapping("/history")
  @PreAuthorize(
      "hasRole('SCHOOL_ADMIN') or hasRole('TEACHER') or @perms.parentLinkedTo(#studentId)")
  @Operation(
      summary = "Get attendance history",
      description =
          "Returns attendance records for a student across an inclusive date range. "
              + "SCHOOL_ADMIN and TEACHER see all records; a PARENT must be linked to the student "
              + "(unlinked parents receive 404 instead of 403 to prevent enumeration).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Attendance history"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(
        responseCode = "403",
        description = "Requires SCHOOL_ADMIN, TEACHER, or linked PARENT"),
    @ApiResponse(
        responseCode = "404",
        description = "Student not found or parent not linked (anti-enumeration)")
  })
  public ResponseEntity<List<AttendanceHistoryEntry>> history(
      @Parameter(description = "Student ID") @RequestParam UUID studentId,
      @Parameter(description = "Range start date (yyyy-MM-dd, inclusive)")
          @RequestParam
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate from,
      @Parameter(description = "Range end date (yyyy-MM-dd, inclusive)")
          @RequestParam
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate to) {
    return ResponseEntity.ok(service.history(studentId, from, to));
  }

  /**
   * Parent reply to an absence alert. {@code @PreAuthorize} only narrows to PARENT role — the
   * per-record linked-child check is in the service layer so an unlinked parent gets 404
   * (anti-enumeration) rather than 403.
   */
  @PostMapping("/{id}/parent-response")
  @PreAuthorize("hasRole('PARENT')")
  @Operation(
      summary = "Submit parent response to absence alert",
      description =
          "Records a parent's explanation or acknowledgment for an absence alert. "
              + "The parent must be linked to the student on the attendance record; "
              + "unlinked parents receive 404 (anti-enumeration).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Parent response recorded"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires PARENT role"),
    @ApiResponse(
        responseCode = "404",
        description = "Attendance record not found or parent not linked (anti-enumeration)"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<AttendanceRecordResponse> parentResponse(
      @PathVariable UUID id,
      @Valid @RequestBody ParentResponseRequest request,
      Authentication authentication) {
    UUID parentUserId = requireParent(authentication).userId();
    return ResponseEntity.ok(service.recordParentResponse(id, parentUserId, request));
  }

  private StaffPrincipal requireStaff(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof StaffPrincipal staff)) {
      throw new TenantSecurityException();
    }
    return staff;
  }

  private ParentPrincipal requireParent(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof ParentPrincipal parent)) {
      throw new TenantSecurityException();
    }
    return parent;
  }
}
