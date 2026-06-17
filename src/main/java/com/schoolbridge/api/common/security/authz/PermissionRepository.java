package com.schoolbridge.api.common.security.authz;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Catalog access for {@link PermissionEntity}. */
public interface PermissionRepository extends JpaRepository<PermissionEntity, UUID> {

  Optional<PermissionEntity> findByName(String name);
}
