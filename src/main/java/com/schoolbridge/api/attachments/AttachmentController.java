package com.schoolbridge.api.attachments;

import com.schoolbridge.api.attachments.dto.AttachmentDownloadTicket;
import com.schoolbridge.api.attachments.dto.AttachmentResponse;
import com.schoolbridge.api.attachments.dto.AttachmentUploadTicket;
import com.schoolbridge.api.attachments.dto.CreateAttachmentRequest;
import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.common.security.authz.RequirePermission;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Attachment upload and download.
 *
 * <p>No endpoint here accepts or returns file bytes. Uploads are a presigned PUT the client
 * performs against object storage; downloads are a short-lived presigned GET. Serving user files
 * from this origin would put a stored-XSS or content-sniffing bug inside the API's own security
 * origin, against an already-authenticated session — see {@code docs/PLAN_FILE_UPLOAD.md} section
 * 2.
 */
@RestController
@RequestMapping(ApiConstants.API_V1 + "/attachments")
@Tag(name = "Attachments", description = "Presigned file upload and download")
public class AttachmentController {

  private final AttachmentService service;

  public AttachmentController(AttachmentService service) {
    this.service = service;
  }

  @PostMapping
  @RequirePermission(Permission.ATTACHMENT_UPLOAD)
  @Operation(
      summary = "Request an upload URL",
      description =
          "Records a PENDING attachment and returns a presigned PUT. The client uploads the bytes "
              + "directly to object storage, then calls /attachments/{id}/complete. The declared "
              + "size is signed into the URL, so object storage itself rejects a body of any other "
              + "length.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Upload ticket issued"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(
        responseCode = "422",
        description = "Size over the cap, or a content type not on the allow-list")
  })
  public ResponseEntity<AttachmentUploadTicket> create(
      @Valid @RequestBody CreateAttachmentRequest request, Authentication authentication) {
    UUID schoolId = TenantContext.require();
    UUID actorId = requireStaff(authentication).userId();
    return ResponseEntity.status(201).body(service.createUpload(schoolId, actorId, request));
  }

  @PostMapping("/{id}/complete")
  @RequirePermission(Permission.ATTACHMENT_UPLOAD)
  @PreAuthorize("hasAnyRole('SCHOOL_ADMIN','SUPER_ADMIN') or @perms.isAttachmentUploader(#id)")
  @Operation(
      summary = "Finish an upload",
      description =
          "Verifies the object exists at the expected size, sniffs its real content type from the "
              + "stored bytes, and scans it. The response carries the terminal status: CLEAN is "
              + "downloadable, REJECTED is not. A rejected or infected object is deleted from "
              + "storage immediately.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Inspection finished; see status"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Attachment not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Bytes were never uploaded, or the attachment is already complete"),
    @ApiResponse(responseCode = "422", description = "Anti-virus reported a signature match")
  })
  public ResponseEntity<AttachmentResponse> complete(@PathVariable UUID id) {
    return ResponseEntity.ok(service.complete(id));
  }

  @GetMapping("/{id}")
  @RequirePermission(Permission.ATTACHMENT_READ)
  @PreAuthorize(
      "hasAnyRole('SCHOOL_ADMIN','SUPER_ADMIN','TEACHER')"
          + " or @perms.parentCanReadAttachment(#id)")
  @Operation(
      summary = "Get attachment metadata",
      description = "Metadata only. The storage key is never exposed.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Attachment metadata"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Attachment not found")
  })
  public ResponseEntity<AttachmentResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(service.get(id));
  }

  @GetMapping("/{id}/download")
  @RequirePermission(Permission.ATTACHMENT_READ)
  @PreAuthorize(
      "hasAnyRole('SCHOOL_ADMIN','SUPER_ADMIN','TEACHER')"
          + " or @perms.parentCanReadAttachment(#id)")
  @Operation(
      summary = "Get a download URL",
      description =
          "Returns a short-lived presigned GET, issued only for a CLEAN attachment. The URL is a "
              + "bearer credential until it expires, so it is minted per request and never stored. "
              + "A PARENT may only download an attachment carried by homework or an announcement "
              + "they are a recipient of.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Download ticket issued"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Attachment not found"),
    @ApiResponse(responseCode = "409", description = "Attachment is not CLEAN")
  })
  public ResponseEntity<AttachmentDownloadTicket> download(@PathVariable UUID id) {
    return ResponseEntity.ok(service.download(id));
  }

  @DeleteMapping("/{id}")
  @RequirePermission(Permission.ATTACHMENT_DELETE)
  @Operation(
      summary = "Delete an attachment",
      description =
          "Deletes the object and the row. Refused with 409 while a homework item or announcement "
              + "still references it, so a published item cannot end up pointing at nothing.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Deleted"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
    @ApiResponse(responseCode = "404", description = "Attachment not found"),
    @ApiResponse(responseCode = "409", description = "Still referenced")
  })
  public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
    service.delete(id, requireStaff(authentication).userId());
    return ResponseEntity.noContent().build();
  }

  private static StaffPrincipal requireStaff(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof StaffPrincipal staff)) {
      throw new TenantSecurityException();
    }
    return staff;
  }
}
