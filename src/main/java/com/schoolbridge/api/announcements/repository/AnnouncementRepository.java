package com.schoolbridge.api.announcements.repository;

import com.schoolbridge.api.announcements.Announcement;
import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link Announcement}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL because Hibernate's {@code @Filter} does
 * NOT apply to {@code EntityManager.find()} (direct primary-key lookups). See {@code
 * UserRepository} for the canonical explanation.
 */
public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

  @Override
  @Query("select a from Announcement a where a.id = :id")
  Optional<Announcement> findById(@Param("id") UUID id);

  Page<Announcement> findAll(Pageable pageable);

  Page<Announcement> findAllByStatus(AnnouncementStatus status, Pageable pageable);
}
