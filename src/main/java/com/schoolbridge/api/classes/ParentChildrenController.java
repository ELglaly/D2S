package com.schoolbridge.api.classes;

import com.schoolbridge.api.classes.dto.ParentChildResponse;
import com.schoolbridge.api.classes.service.ParentChildrenService;
import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.common.web.ApiConstants;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiConstants.API_V1 + "/parents")
@Tag(name = "Parent Portal", description = "Parent-facing endpoints")
public class ParentChildrenController {

  private final ParentChildrenService service;

  public ParentChildrenController(ParentChildrenService service) {
    this.service = service;
  }

  @GetMapping("/me/children")
  @PreAuthorize("hasRole('PARENT')")
  @Operation(
      summary = "List my children",
      description =
          "Returns the children linked to the authenticated parent, each enriched with "
              + "their current class enrolments. Returns an empty list when no children are linked.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Children list (may be empty)"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Requires PARENT role")
  })
  public ResponseEntity<List<ParentChildResponse>> myChildren(Authentication authentication) {
    ParentPrincipal parent = requireParent(authentication);
    return ResponseEntity.ok(service.listChildren(parent.userId()));
  }

  private ParentPrincipal requireParent(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof ParentPrincipal parent)) {
      throw new TenantSecurityException();
    }
    return parent;
  }
}

