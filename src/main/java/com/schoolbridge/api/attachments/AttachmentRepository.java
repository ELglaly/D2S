package com.schoolbridge.api.attachments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link Attachment}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL because Hibernate's {@code @Filter} does
 * NOT apply to {@code EntityManager.find()}, which is what the default implementation calls — see
 * {@code docs/COMMON_MISTAKES.md} section 1.
 *
 * <p>The two sweeper queries deliberately do <em>not</em> filter by school: they run outside any
 * request, with no tenant bound, and must drain every school from one pass. They are the reason
 * {@code AttachmentSweeper} takes the tenant bypass explicitly rather than by accident.
 */
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

  @Override
  @Query("select a from Attachment a where a.id = :id")
  Optional<Attachment> findById(@Param("id") UUID id);

  /**
   * Uploads that were never completed. A client handed an upload URL that never calls {@code
   * complete} leaves a row, and possibly an object, that nothing will ever reference.
   */
  @Query(
      "select a from Attachment a "
          + "where a.status in (com.schoolbridge.api.attachments.AttachmentStatus.PENDING, "
          + "                   com.schoolbridge.api.attachments.AttachmentStatus.UPLOADED) "
          + "  and a.createdAt < :cutoff "
          + "order by a.createdAt asc")
  List<Attachment> findAbandoned(@Param("cutoff") Instant cutoff, Pageable pageable);

  /** Stored attachments past the retention window. */
  @Query(
      "select a from Attachment a "
          + "where a.status = com.schoolbridge.api.attachments.AttachmentStatus.CLEAN "
          + "  and a.createdAt < :cutoff "
          + "order by a.createdAt asc")
  List<Attachment> findExpired(@Param("cutoff") Instant cutoff, Pageable pageable);
}
