package com.schoolbridge.api.announcements.repository;

import com.schoolbridge.api.announcements.AnnouncementRecipient;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
 * <p>{@link #findById} is overridden with explicit JPQL â€” see {@code UserRepository} for rationale.
 */
public interface AnnouncementRecipientRepository
    extends JpaRepository<AnnouncementRecipient, UUID> {

  @Override
  @Query("select r from AnnouncementRecipient r where r.id = :id")
  Optional<AnnouncementRecipient> findById(@Param("id") UUID id);

  Page<AnnouncementRecipient> findAllByAnnouncementId(UUID announcementId, Pageable pageable);

  long countByAnnouncementId(UUID announcementId);

  /**
   * Recipient counts for a whole page of announcements in one grouped query. The list endpoint used
   * to call {@link #countByAnnouncementId} per row â€” 20 extra round-trips per page, against the
   * largest table in the schema. Announcements with no recipients are simply absent from the
   * result, so callers must default them to zero.
   */
  @Query(
      "select r.announcementId, count(r) from AnnouncementRecipient r "
          + "where r.announcementId in :announcementIds "
          + "group by r.announcementId")
  List<Object[]> countByAnnouncementIdIn(
      @Param("announcementIds") Collection<UUID> announcementIds);

  /**
   * Every recipient row a parent holds for one announcement â€” one per linked child, since the table
   * is keyed {@code (announcement_id, parent_user_id, student_id)}. Acknowledgement must span all
   * of them: a parent with two children in scope sees one announcement and taps acknowledge once,
   * and the earlier {@code findFirst...} variant left the sibling rows unacknowledged forever.
   */
  List<AnnouncementRecipient> findAllByAnnouncementIdAndParentUserId(
      UUID announcementId, UUID parentUserId);

  boolean existsByAnnouncementIdAndParentUserId(UUID announcementId, UUID parentUserId);

  /**
   * True when the parent is a recipient of some announcement carrying this attachment. Backs the
   * download authorization check â€” the attachment reference lives on the announcement, not on the
   * recipient row, so this joins rather than deriving.
   */
  @Query(
      "select count(r) > 0 from AnnouncementRecipient r, Announcement a "
          + "where r.announcementId = a.id "
          + "  and r.parentUserId = :parentUserId "
          + "  and a.attachmentKey = :attachmentKey")
  boolean existsForParentAndAttachment(
      @Param("parentUserId") UUID parentUserId, @Param("attachmentKey") String attachmentKey);

  /**
   * Deferred recipients whose quiet-hours hold has expired, across every school. Released by {@code
   * AnnouncementDeferralSweeper}, which runs without a bound {@code TenantContext} and re-binds the
   * tenant per row â€” the same shape as {@code AttendanceAlertRecipientRepository}'s equivalent.
   *
   * <p>The {@code messageId is null} guard is what makes a redelivery harmless: a row that already
   * reached a provider cannot be picked up again by the release scan.
   */
  @Query(
      "select r from AnnouncementRecipient r "
          + "where r.deliveryStatus = com.schoolbridge.api.announcements.enums.DeliveryStatus.DEFERRED "
          + "  and r.deferredUntil <= :now "
          + "  and r.messageId is null")
  List<AnnouncementRecipient> findDeferredReadyToDispatch(@Param("now") Instant now);

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
   * row is visible across schools â€” which is the desired behavior, since Meta only echoes statuses
   * for message ids we previously issued.
   */
  @Query("select r from AnnouncementRecipient r where r.messageId = :messageId")
  Optional<AnnouncementRecipient> findByMessageId(@Param("messageId") String messageId);
}

