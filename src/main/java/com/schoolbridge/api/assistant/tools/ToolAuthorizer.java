package com.schoolbridge.api.assistant.tools;

import com.schoolbridge.api.common.security.authz.Permission;

import com.schoolbridge.api.common.security.authz.EffectivePermissionService;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The single authorization boundary for assistant tools. A caller may see and run a {@link Tool}
 * iff their role holds at least one of the tool's {@link Tool#permissions()} in the DB-backed
 * {@code role_permissions} grants (via {@link EffectivePermissionService}, cached by role) â€” so
 * re-mapping permissions at runtime constrains the assistant without a redeploy.
 *
 * <p>Used by {@link ToolRegistry} at catalog build, and inherited by the execution path through
 * {@link ToolRegistry#find}. Row-level scope checks (own child, own class) remain inside the tools.
 */
@Component
public class ToolAuthorizer {

  private final EffectivePermissionService permissions;

  public ToolAuthorizer(EffectivePermissionService permissions) {
    this.permissions = permissions;
  }

  /** The caller's effective permission names. Resolve once per request, then reuse. */
  public Set<String> grantsFor(ToolContext ctx) {
    return permissions.permissionsForRole(ctx.role());
  }

  public boolean canUseTool(Tool tool, ToolContext ctx) {
    return canUseTool(tool, grantsFor(ctx));
  }

  /**
   * Overload taking the already-resolved grant set so a catalog build of N tools performs a single
   * permission lookup, not N. The caller is authorized iff they hold any of the tool's permissions.
   */
  public boolean canUseTool(Tool tool, Set<String> granted) {
    return tool.permissions().stream().map(Enum::name).anyMatch(granted::contains);
  }
}

