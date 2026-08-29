package com.schoolbridge.api.notifications;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link NotificationPreference}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL because Hibernate's {@code @Filter} does
 * NOT apply to {@code EntityManager.find()} (direct primary-key lookups). See {@code
 * UserRepository} for the canonical explanation.
 */
public interface NotificationPreferenceRepository
    extends JpaRepository<NotificationPreference, UUID> {

  @Override
  @Query("select p from NotificationPreference p where p.id = :id")
  Optional<NotificationPreference> findById(@Param("id") UUID id);

  @Query("select p from NotificationPreference p where p.userId = :userId")
  List<NotificationPreference> findAllByUserId(@Param("userId") UUID userId);

  @Query(
      "select p from NotificationPreference p "
          + "where p.userId = :userId and p.category = :category")
  Optional<NotificationPreference> findByUserIdAndCategory(
      @Param("userId") UUID userId, @Param("category") NotificationCategory category);
}

