package com.schoolbridge.api.common.security.authz;

import com.schoolbridge.api.identity.UserRole;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin operations on roleâ†’permission mappings. Every mutation evicts the affected role's cached
 * permission set so the change takes effect immediately (see {@link EffectivePermissionService}).
 */
@Service
public class RolePermissionAdminService {

  private final PermissionRepository permissions;
  private final RolePermissionRepository rolePermissions;
  private final EffectivePermissionService effectivePermissions;

  public RolePermissionAdminService(
      PermissionRepository permissions,
      RolePermissionRepository rolePermissions,
      EffectivePermissionService effectivePermissions) {
    this.permissions = permissions;
    this.rolePermissions = rolePermissions;
    this.effectivePermissions = effectivePermissions;
  }

  @Transactional(readOnly = true)
  public List<PermissionEntity> catalog() {
    return permissions.findAll();
  }

  @Transactional(readOnly = true)
  public List<String> permissionsOf(UserRole role) {
    return rolePermissions.listPermissionNamesByRole(role);
  }

  /** Idempotent grant. Self-heals a missing catalog row so the enum stays the source of truth. */
  @Transactional
  public void grant(UserRole role, Permission permission) {
    PermissionEntity entity =
        permissions
            .findByName(permission.name())
            .orElseGet(
                () ->
                    permissions.save(
                        new PermissionEntity(UUID.randomUUID(), permission.name(), null)));
    if (!rolePermissions.existsByRoleAndPermission_Name(role, permission.name())) {
      rolePermissions.save(new RolePermission(role, entity));
    }
    effectivePermissions.evictRole(role);
  }

  @Transactional
  public void revoke(UserRole role, Permission permission) {
    rolePermissions.deleteByRoleAndPermissionName(role, permission.name());
    effectivePermissions.evictRole(role);
  }
}

