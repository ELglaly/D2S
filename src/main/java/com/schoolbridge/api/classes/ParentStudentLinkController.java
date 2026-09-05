package com.schoolbridge.api.classes;

import com.schoolbridge.api.classes.dto.CreateParentLinkRequest;
import com.schoolbridge.api.classes.dto.ParentStudentLinkResponse;
import com.schoolbridge.api.classes.service.ParentStudentLinkService;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.common.security.authz.RequirePermission;
import com.schoolbridge.api.common.tenancy.TenantContext;
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
@RequestMapping(ApiConstants.API_V1 + "/parent-links")
@Tag(name = "Parent-Student Links", description = "Link parents to their children")
@RequirePermission(Permission.PARENT_LINK_MANAGE)
public class ParentStudentLinkController {

  private final ParentStudentLinkService service;

  public ParentStudentLinkController(ParentStudentLinkService service) {
    this.service = service;
  }

  @PostMapping
  @Operation(
      summary = "Create parent-student link",
      description =
          "Links a parent user to a student, recording their relationship type and whether "
              + "they are the primary contact. A parent may be linked to multiple students; "
              + "each link is a separate record.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Link created"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Parent user or student not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Link already exists for this parent-student pair"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<ParentStudentLinkResponse> create(
      @Valid @RequestBody CreateParentLinkRequest request) {
    UUID schoolId = TenantContext.require();
    ParentStudentLinkResponse created = service.create(schoolId, request);
    return ResponseEntity.created(URI.create(ApiConstants.API_V1 + "/parent-links/" + created.id()))
        .body(created);
  }

  @GetMapping("/student/{studentId}")
  @Operation(
      summary = "List parent links for a student",
      description = "Returns all parent-student links for the given student.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Parent link list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Student not found")
  })
  public ResponseEntity<List<ParentStudentLinkResponse>> listByStudent(
      @PathVariable UUID studentId) {
    return ResponseEntity.ok(service.listByStudent(studentId));
  }

  @DeleteMapping("/{id}")
  @Operation(
      summary = "Remove parent-student link",
      description = "Deletes the link between a parent and a student.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Link removed"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Link not found")
  })
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
