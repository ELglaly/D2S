package com.schoolbridge.api.homework;

import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.common.security.AuthorizationPolicy;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.common.web.PageResponse;
import com.schoolbridge.api.homework.dto.CreateHomeworkRequest;
import com.schoolbridge.api.homework.dto.HomeworkRecipientResponse;
import com.schoolbridge.api.homework.dto.HomeworkResponse;
import com.schoolbridge.api.homework.dto.ParentHomeworkFeedEntry;
import com.schoolbridge.api.homework.dto.UpdateHomeworkRequest;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.common.security.authz.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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
@RequestMapping(ApiConstants.API_V1 + "/homework")
@Tag(name = "Homework", description = "Homework assignment and parent feed")
public class HomeworkController {

  private final HomeworkService service;
  private final AuthorizationPolicy authorization;

  public HomeworkController(HomeworkService service, AuthorizationPolicy authorization) {
    this.service = service;
    this.authorization = authorization;
  }

  @PostMapping
  @RequirePermission(Permission.HOMEWORK_CREATE)
  @Operation(
      summary = "Create homework item",
      description =
          "Creates a homework item as DRAFT (default) or PUBLISHED (when publish=true). "
              + "PUBLISHED items immediately materialize HomeworkRecipient rows for every "
              + "linked parent of every enrolled student in the class.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Homework item created"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "422", description = "Validation error")
  })
  public ResponseEntity<HomeworkResponse> create(
      @Valid @RequestBody CreateHomeworkRequest request, Authentication authentication) {
    UUID schoolId = TenantContext.require();
    authorization.requireClassAccess(request.classId());
    UUID actorId = requireStaff(authentication).userId();
    HomeworkResponse created = service.create(schoolId, actorId, request);
    return ResponseEntity.created(URI.create(ApiConstants.API_V1 + "/homework/" + created.id()))
        .body(created);
  }

  @PostMapping("/{id}/publish")
  @RequirePermission(Permission.HOMEWORK_PUBLISH)
  @Operation(
      summary = "Publish homework item",
      description =
          "Transitions a DRAFT homework item to PUBLISHED and materializes recipient rows. "
              + "409 if already PUBLISHED or ARCHIVED.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Published"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Homework item not found"),
    @ApiResponse(responseCode = "409", description = "Already published or archived")
  })
  public ResponseEntity<HomeworkResponse> publish(@PathVariable UUID id) {
    authorization.requireHomeworkAccess(id);
    return ResponseEntity.ok(service.publish(id));
  }

  @GetMapping
  @RequirePermission(Permission.HOMEWORK_UPDATE)
  @Operation(
      summary = "List homework items (staff)",
      description = "Returns paginated homework items filtered by classId, status, and date range.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated results"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
  })
  public ResponseEntity<PageResponse<HomeworkResponse>> staffList(
      @RequestParam(required = false) UUID classId,
      @RequestParam(required = false) HomeworkStatus status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate dueDateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate dueDateTo,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    if (classId != null) authorization.requireClassRead(classId);
    return ResponseEntity.ok(
        PageResponse.from(service.list(classId, status, dueDateFrom, dueDateTo, pageable)));
  }

  @GetMapping(params = "childId")
  @RequirePermission(Permission.HOMEWORK_READ)
  @Operation(
      summary = "Parent homework feed",
      description =
          "Returns the homework feed for the specified child. "
              + "404 if the parent is not linked to the child (anti-enumeration).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated feed"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(
        responseCode = "404",
        description = "Child not linked to parent (anti-enumeration)")
  })
  public ResponseEntity<PageResponse<ParentHomeworkFeedEntry>> parentFeedEndpoint(
      @Parameter(description = "ID of the child to fetch homework for") @RequestParam UUID childId,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable,
      Authentication authentication) {
    ParentPrincipal parent = requireParent(authentication);
    Page<ParentHomeworkFeedEntry> feed = service.parentFeed(parent.userId(), childId, pageable);
    return ResponseEntity.ok(PageResponse.from(feed));
  }

  @GetMapping("/{id}")
  @RequirePermission(Permission.HOMEWORK_READ)
  @Operation(
      summary = "Get homework item",
      description =
          "Returns a homework item. Parents receive 404 if their child is not a recipient "
              + "(anti-enumeration).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Homework item found"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(
        responseCode = "404",
        description = "Not found or parent not a recipient (anti-enumeration)")
  })
  public ResponseEntity<HomeworkResponse> get(
      @PathVariable UUID id, Authentication authentication) {
    if (authentication != null && authentication.getPrincipal() instanceof ParentPrincipal parent) {
      return ResponseEntity.ok(service.findByIdForParent(id, parent.userId()));
    }
    return ResponseEntity.ok(service.findById(id));
  }

  @GetMapping("/{id}/recipients")
  @RequirePermission(Permission.HOMEWORK_UPDATE)
  @Operation(
      summary = "List homework recipients",
      description =
          "Returns all recipient rows for a homework item, including delivery status and "
              + "acknowledgment timestamp. Only the item's author teacher or a SCHOOL_ADMIN "
              + "can access this endpoint.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Recipient list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Homework item not found")
  })
  public ResponseEntity<List<HomeworkRecipientResponse>> listRecipients(@PathVariable UUID id) {
    authorization.requireHomeworkAccess(id);
    return ResponseEntity.ok(service.listRecipients(id));
  }

  @PatchMapping("/{id}")
  @RequirePermission(Permission.HOMEWORK_UPDATE)
  @Operation(
      summary = "Update homework item",
      description =
          "Updates subject, description, attachmentKey, and dueDate on a DRAFT or PUBLISHED item. "
              + "Recipients are not re-materialized. 409 if ARCHIVED.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Updated"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Homework item not found"),
    @ApiResponse(responseCode = "409", description = "Item is ARCHIVED and cannot be modified")
  })
  public ResponseEntity<HomeworkResponse> update(
      @PathVariable UUID id, @Valid @RequestBody UpdateHomeworkRequest request) {
    authorization.requireHomeworkAccess(id);
    return ResponseEntity.ok(service.update(id, request));
  }

  @DeleteMapping("/{id}")
  @RequirePermission(Permission.HOMEWORK_DELETE)
  @Operation(
      summary = "Archive homework item",
      description =
          "Soft-deletes by transitioning status to ARCHIVED. "
              + "HomeworkRecipient rows are retained for parent-feed history.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Archived"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Homework item not found")
  })
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    authorization.requireHomeworkAccess(id);
    service.archive(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/acknowledge")
  @RequirePermission(Permission.HOMEWORK_ACK)
  @Operation(
      summary = "Acknowledge homework item",
      description =
          "Records a parent's acknowledgment. No-op when requiresAck=false. "
              + "404 if the parent has no recipient row for this item.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Acknowledged"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(
        responseCode = "404",
        description = "Parent has no recipient row for this homework item")
  })
  public ResponseEntity<Void> acknowledge(@PathVariable UUID id, Authentication authentication) {
    UUID parentUserId = requireParent(authentication).userId();
    service.acknowledge(id, parentUserId);
    return ResponseEntity.noContent().build();
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

