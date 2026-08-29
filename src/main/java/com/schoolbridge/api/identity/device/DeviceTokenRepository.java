package com.schoolbridge.api.identity.device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for device push tokens.
 *
 * <p>{@link #findById} is overridden with explicit JPQL so Hibernate's {@code @Filter} (which does
 * not apply to {@code EntityManager.find}) still scopes the lookup to the current school.
 */
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

  @Override
  @Query("select d from DeviceToken d where d.id = :id")
  Optional<DeviceToken> findById(@Param("id") UUID id);

  @Query("select d from DeviceToken d where d.userId = :userId and d.deviceId = :deviceId")
  Optional<DeviceToken> findByUserIdAndDeviceId(
      @Param("userId") UUID userId, @Param("deviceId") String deviceId);

  @Query("select d from DeviceToken d where d.userId = :userId and d.active = true")
  List<DeviceToken> findActiveByUserId(@Param("userId") UUID userId);
}

