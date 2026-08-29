package com.schoolbridge.api.announcements;

import com.schoolbridge.api.announcements.dto.AnnouncementRecipientResponse;
import com.schoolbridge.api.announcements.dto.AnnouncementResponse;
import com.schoolbridge.api.announcements.dto.CreateAnnouncementRequest;
import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import com.schoolbridge.api.announcements.service.AnnouncementService;
import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.common.security.AuthorizationPolicy;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.common.web.PageResponse;
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
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiConstants.API_V1 + "/announcements")
@Tag(
    name = "Announcements",
    description = "School-wide and class-level announcements with multi-channel delivery")
public class AnnouncementController {

  private final AnnouncementService service;
  private final AuthorizationPolicy authorization;

  public AnnouncementController(AnnouncementService service, AuthorizationPolicy authorization) {
    this.service = service;
    this.authorization = authorization;
  }

  @PostMapping
  @RequirePermission(Permission.ANNOUNCEMENT_SEND)
  @Operation(
      summary = "Create announcement",
      description =
          "Creates and fans out an announcement to matching parent recipients. "
              + "SCHOOL_ADMIN may use any scope (SCHOOL, GRADE, CLASS, CUSTOM). "
              + "TEACHER may only use CLASS scope for a class they are assigned to. "
              + "Recipients are derived from linked parents of enrolled students matching the scope.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Announcement created and fanout queued"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(
        responseCode = "403",
        description = "Insufficient permissions or scope not allowed for role"),
    @ApiResponse(
        responseCode = "422",
        description = "Invalid scope combination or body validation error")
  })
  public ResponseEntity<AnnouncementResponse> create(
      @Valid @RequestBody CreateAnnouncementRequest request, Authentication authentication) {
    UUID schoolId = TenantContext.require();
    authorization.requireAnnouncementScope(request.scopeType(), request.classId());
    UUID senderId = requireStaff(authentication).userId();
    AnnouncementResponse created = service.create(schoolId, senderId, request);
    return ResponseEntity.created(
            URI.create(ApiConstants.API_V1 + "/announcements/" + created.id()))
        .body(created);
  }

  @GetMapping
  @RequirePermission(Permission.ANNOUNCEMENT_MANAGE)
  @Operation(
      summary = "List announcements",
      description =
          "Returns a paginated list of all announcements for the school, newest first. "
              + "Filter by status to narrow results.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated announcement list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN")
  })
  public ResponseEntity<PageResponse<AnnouncementResponse>> list(
      @Parameter(description = "Filter by announcement status; omit to return all statuses")
          @RequestParam(required = false)
          AnnouncementStatus status,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return ResponseEntity.ok(PageResponse.from(service.list(status, pageable)));
  }

  @GetMapping("/{id}")
  @RequirePermission(Permission.ANNOUNCEMENT_SEND)
  @Operation(summary = "Get announcement", description = "Returns a single announcement by ID.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Announcement found"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Not the sender and not SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Announcement not found")
  })
  public ResponseEntity<AnnouncementResponse> get(@PathVariable UUID id) {
    authorization.requireAnnouncementAccess(id);
    return ResponseEntity.ok(service.findById(id));
  }

  @PostMapping("/{id}/recall")
  @RequirePermission(Permission.ANNOUNCEMENT_MANAGE)
  @Operation(
      summary = "Recall announcement",
      description =
          "Marks the announcement as RECALLED. Already-delivered messages are not retracted "
              + "from recipients' devices.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Announcement recalled"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Announcement not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Announcement is already recalled or in a non-recallable state")
  })
  public ResponseEntity<AnnouncementResponse> recall(@PathVariable UUID id) {
    return ResponseEntity.ok(service.recall(id));
  }

  @GetMapping("/{id}/recipients")
  @RequirePermission(Permission.ANNOUNCEMENT_MANAGE)
  @Operation(
      summary = "List recipients",
      description =
          "Returns the materialized recipient rows for an announcement, including per-recipient "
              + "delivery status (QUEUEDÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â‚¬Å¾Ã‚Â¢SENTÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â‚¬Å¾Ã‚Â¢DELIVEREDÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â‚¬Å¾Ã‚Â¢READ) and acknowledgment timestamp.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated recipient list"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires SCHOOL_ADMIN"),
    @ApiResponse(responseCode = "404", description = "Announcement not found")
  })
  public ResponseEntity<PageResponse<AnnouncementRecipientResponse>> listRecipients(
      @PathVariable UUID id,
      @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.ASC)
          Pageable pageable) {
    return ResponseEntity.ok(PageResponse.from(service.listRecipients(id, pageable)));
  }

  @PostMapping("/{id}/acknowledge")
  @RequirePermission(Permission.ANNOUNCEMENT_READ)
  @Operation(
      summary = "Acknowledge announcement",
      description =
          "Records a parent's acknowledgment for an announcement where requiresAck=true. "
              + "Idempotent ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â re-acknowledging the same announcement is a no-op.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Acknowledged"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(
        responseCode = "403",
        description = "Parent is not a recipient of this announcement"),
    @ApiResponse(responseCode = "404", description = "Announcement not found")
  })
  public ResponseEntity<Void> acknowledge(@PathVariable UUID id, Authentication authentication) {
    authorization.requireAnnouncementRecipient(id);
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

