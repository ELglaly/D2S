package com.schoolbridge.api.announcements.repository;

import com.schoolbridge.api.announcements.AnnouncementRecipient;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link AnnouncementRecipient}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL — see {@code UserRepository} for rationale.
 */
public interface AnnouncementRecipientRepository
    extends JpaRepository<AnnouncementRecipient, UUID> {

  @Override
  @Query("select r from AnnouncementRecipient r where r.id = :id")
  Optional<AnnouncementRecipient> findById(@Param("id") UUID id);

  Page<AnnouncementRecipient> findAllByAnnouncementId(UUID announcementId, Pageable pageable);

  long countByAnnouncementId(UUID announcementId);

  Optional<AnnouncementRecipient> findFirstByAnnouncementIdAndParentUserId(
      UUID announcementId, UUID parentUserId);

  boolean existsByAnnouncementIdAndParentUserId(UUID announcementId, UUID parentUserId);

  /**
   * Unacknowledged recipient rows for a parent, newest first. Tenant-scoped via the active
   * {@code @Filter} (a derived query, not the {@code findById} fast-path that bypasses it). Backs
   * the assistant's {@code get_unacknowledged_announcements} read.
   */
  Page<AnnouncementRecipient> findAllByParentUserIdAndAcknowledgedAtIsNull(
      UUID parentUserId, Pageable pageable);

  /**
   * Finder used by the WhatsApp delivery-status webhook. Routed through JPQL so the tenant filter
   * applies when a {@link com.schoolbridge.api.common.tenancy.TenantContext} is bound; when invoked
   * without a tenant (the webhook is unauthenticated and global), the filter stays disabled and the
   * row is visible across schools — which is the desired behavior, since Meta only echoes statuses
   * for message ids we previously issued.
   */
  @Query("select r from AnnouncementRecipient r where r.messageId = :messageId")
  Optional<AnnouncementRecipient> findByMessageId(@Param("messageId") String messageId);
}
