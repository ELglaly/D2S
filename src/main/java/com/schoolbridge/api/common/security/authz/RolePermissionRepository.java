package com.schoolbridge.api.common.security.authz;

import com.schoolbridge.api.identity.UserRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Roleâ†’permission mapping access. */
public interface RolePermissionRepository extends JpaRepository<RolePermission, UUID> {

  /**
   * Effective permission names for a role in a single join query (no N+1). Returned as a flat set
   * of strings â€” all the aspect needs.
   */
  @Query("select rp.permission.name from RolePermission rp where rp.role = :role")
  Set<String> findPermissionNamesByRole(@Param("role") UserRole role);

  @Query(
      "select rp.permission.name from RolePermission rp where rp.role = :role"
          + " order by rp.permission.name")
  List<String> listPermissionNamesByRole(@Param("role") UserRole role);

  boolean existsByRoleAndPermission_Name(UserRole role, String permissionName);

  @Modifying
  @Query("delete from RolePermission rp where rp.role = :role and rp.permission.name = :name")
  int deleteByRoleAndPermissionName(@Param("role") UserRole role, @Param("name") String name);
}
