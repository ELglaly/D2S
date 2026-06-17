package com.schoolbridge.api.common.security.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Pure-unit test for {@link PermissionCatalogReconciler} — mocked repository, no DB. */
class PermissionCatalogReconcilerTest {

  private final PermissionRepository permissions = mock(PermissionRepository.class);
  private final PermissionCatalogReconciler reconciler =
      new PermissionCatalogReconciler(permissions);

  @Test
  void insertsEnumValuesMissingFromTheCatalog() {
    // Catalog has every permission except MANAGE_PERMISSIONS.
    List<PermissionEntity> stored =
        Arrays.stream(Permission.values())
            .filter(p -> p != Permission.MANAGE_PERMISSIONS)
            .map(p -> new PermissionEntity(UUID.randomUUID(), p.name(), null))
            .toList();
    when(permissions.findAll()).thenReturn(stored);

    reconciler.reconcile();

    ArgumentCaptor<PermissionEntity> captor = ArgumentCaptor.forClass(PermissionEntity.class);
    verify(permissions).save(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo(Permission.MANAGE_PERMISSIONS.name());
  }

  @Test
  void noWritesWhenCatalogAlreadyComplete() {
    List<PermissionEntity> stored =
        Arrays.stream(Permission.values())
            .map(p -> new PermissionEntity(UUID.randomUUID(), p.name(), null))
            .toList();
    when(permissions.findAll()).thenReturn(stored);

    reconciler.reconcile();

    verify(permissions, never()).save(any());
  }
}
