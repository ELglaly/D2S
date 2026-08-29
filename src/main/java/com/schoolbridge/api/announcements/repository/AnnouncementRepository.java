package com.schoolbridge.api.announcements.repository;

import com.schoolbridge.api.announcements.Announcement;
import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import java.time.Instant;
import java.util.List;
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

  /** Announcements referencing this attachment. Blocks deleting one that is still in use. */
  @Query("select count(a) from Announcement a where a.attachmentKey = :attachmentKey")
  long countReferencingAttachment(@Param("attachmentKey") String attachmentKey);

  Page<Announcement> findAll(Pageable pageable);

  Page<Announcement> findAllByStatus(AnnouncementStatus status, Pageable pageable);

  /**
   * SCHEDULED announcements whose send time has arrived, across every school.
   *
   * <p>Deliberately <b>not</b> tenant-filtered: the sweeper runs without a bound {@code
   * TenantContext} and must see every school's due announcements. It re-binds the tenant per row
   * before touching anything else, so the cross-tenant read stops here.
   */
  @Query(
      "select a from Announcement a "
          + "where a.status = com.schoolbridge.api.announcements.enums.AnnouncementStatus.SCHEDULED "
          + "and a.scheduledFor <= :now "
          + "order by a.scheduledFor asc")
  List<Announcement> findDueScheduled(@Param("now") Instant now, Pageable pageable);
}

