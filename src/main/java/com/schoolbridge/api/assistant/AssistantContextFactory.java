package com.schoolbridge.api.assistant;

import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.common.error.TenantSecurityException;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.SchoolScopedPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Resolves the per-request {@link ToolContext} from the authenticated principal and the bound
 * tenant, the same way {@code AssistantController} does. Shared so the conversation endpoints never
 * re-implement principal/tenant resolution.
 */
@Component
public class AssistantContextFactory {

  public ToolContext fromAuthentication(Authentication authentication, Locale language) {
    UUID schoolId = TenantContext.require();
    SchoolScopedPrincipal principal = principal(authentication);
    UserRole role = principal instanceof StaffPrincipal staff ? staff.role() : UserRole.PARENT;
    return new ToolContext(schoolId, principal, role, language, null);
  }

  private SchoolScopedPrincipal principal(Authentication authentication) {
    if (authentication != null
        && authentication.getPrincipal() instanceof SchoolScopedPrincipal principal) {
      return principal;
    }
    throw new TenantSecurityException();
  }
}

