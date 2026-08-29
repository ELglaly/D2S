package com.schoolbridge.api.assistant.settings;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link AssistantSettings} (one row per school).
 *
 * <p>{@link #findById} is overridden with explicit JPQL because Hibernate's {@code @Filter} does
 * NOT apply to {@code EntityManager.find()} (direct primary-key lookups).
 */
public interface AssistantSettingsRepository extends JpaRepository<AssistantSettings, UUID> {

  @Override
  @Query("select s from AssistantSettings s where s.id = :id")
  Optional<AssistantSettings> findById(@Param("id") UUID id);

  @Query("select s from AssistantSettings s where s.schoolId = :schoolId")
  Optional<AssistantSettings> findBySchoolId(@Param("schoolId") UUID schoolId);
}

