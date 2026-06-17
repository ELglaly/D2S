package com.schoolbridge.api.common.security.authz;

import com.schoolbridge.api.identity.UserRole;
import java.util.Set;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the effective permissions of a {@link UserRole}, cached by role. Because every user has
 * a single role, this is also a user's effective permission set.
 *
 * <p>Cache hits avoid the DB entirely. Mutations to {@code role_permissions} (via {@link
 * RolePermissionAdminService}) call {@link #evictRole}/{@link #evictAll} so changes take effect
 * without re-login. NOTE: caching works through the Spring proxy only — never self-invoke these
 * methods from within this class.
 */
@Service
public class EffectivePermissionService {

  private final RolePermissionRepository rolePermissions;

  public EffectivePermissionService(RolePermissionRepository rolePermissions) {
    this.rolePermissions = rolePermissions;
  }

  @Transactional(readOnly = true)
  @Cacheable(cacheNames = AuthzCacheConfig.ROLE_PERMISSIONS_CACHE, key = "#role")
  public Set<String> permissionsForRole(UserRole role) {
    return rolePermissions.findPermissionNamesByRole(role);
  }

  @CacheEvict(cacheNames = AuthzCacheConfig.ROLE_PERMISSIONS_CACHE, key = "#role")
  public void evictRole(UserRole role) {
    // Eviction handled by the annotation; body intentionally empty.
  }

  @CacheEvict(cacheNames = AuthzCacheConfig.ROLE_PERMISSIONS_CACHE, allEntries = true)
  public void evictAll() {
    // Eviction handled by the annotation; body intentionally empty.
  }
}
