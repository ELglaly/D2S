package com.schoolbridge.api.notifications;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link NotificationSettings}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL because Hibernate's {@code @Filter} does
 * NOT apply to {@code EntityManager.find()} (direct primary-key lookups). See {@code
 * UserRepository} for the canonical explanation.
 */
public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, UUID> {

  @Override
  @Query("select s from NotificationSettings s where s.id = :id")
  Optional<NotificationSettings> findById(@Param("id") UUID id);

  @Query("select s from NotificationSettings s where s.userId = :userId")
  Optional<NotificationSettings> findByUserId(@Param("userId") UUID userId);
}

