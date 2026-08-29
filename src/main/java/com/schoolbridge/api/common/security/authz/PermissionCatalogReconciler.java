package com.schoolbridge.api.common.security.authz;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Keeps the {@code permissions} catalog table in sync with the {@link Permission} enum on startup.
 * A newly deployed enum value that the Liquibase seed predates is inserted here so {@link
 * RolePermissionAdminService} can grant it immediately.
 *
 * <p>Insert-only: missing rows are added, never removed (a retired enum value leaves a harmless
 * orphan row). New permissions are NOT granted to any role â€” that is an explicit admin action â€” so
 * the secure default for an unknown permission is "no role has it".
 *
 * <p>Each row is saved in its own transaction with the unique-constraint violation swallowed, so
 * two instances starting concurrently cannot fail each other (one wins the insert, the other
 * no-ops).
 */
@Component
public class PermissionCatalogReconciler {

  private static final Logger log = LoggerFactory.getLogger(PermissionCatalogReconciler.class);

  private final PermissionRepository permissions;

  public PermissionCatalogReconciler(PermissionRepository permissions) {
    this.permissions = permissions;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void reconcile() {
    Set<String> existing =
        permissions.findAll().stream().map(PermissionEntity::getName).collect(Collectors.toSet());

    int inserted = 0;
    for (Permission permission : Permission.values()) {
      if (existing.contains(permission.name())) {
        continue;
      }
      try {
        permissions.save(new PermissionEntity(UUID.randomUUID(), permission.name(), null));
        inserted++;
      } catch (DataIntegrityViolationException race) {
        // A concurrent instance inserted the same permission first â€” safe to ignore.
        log.debug("permission_catalog_insert_race name={}", permission.name());
      }
    }

    if (inserted > 0) {
      log.info(
          "permission_catalog_reconciled inserted={} catalog_size={}",
          inserted,
          Permission.values().length);
    }
  }
}

