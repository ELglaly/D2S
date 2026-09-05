package com.schoolbridge.api.homework;

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
 * Tenant-scoped repository for {@link HomeworkRecipient}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL â€” see {@code UserRepository} for
 * rationale. The webhook lookup {@link #findByMessageId} is intentionally global (no tenant
 * binding) â€” Meta only echoes statuses for message ids we issued.
 */
public interface HomeworkRecipientRepository extends JpaRepository<HomeworkRecipient, UUID> {

  @Override
  @Query("select r from HomeworkRecipient r where r.id = :id")
  Optional<HomeworkRecipient> findById(@Param("id") UUID id);

  List<HomeworkRecipient> findAllByHomeworkId(UUID homeworkId);

  /**
   * True when the parent is a recipient of some homework item carrying this attachment. Backs the
   * download authorization check: a parent may open the photo on their own child's homework, and
   * nothing else. Joined rather than derived because the attachment reference lives on the item,
   * not on the recipient row.
   */
  @Query(
      "select count(r) > 0 from HomeworkRecipient r, HomeworkItem h "
          + "where r.homeworkId = h.id "
          + "  and r.parentUserId = :parentUserId "
          + "  and h.attachmentKey = :attachmentKey")
  boolean existsForParentAndAttachment(
      @Param("parentUserId") UUID parentUserId, @Param("attachmentKey") String attachmentKey);

  long countByHomeworkId(UUID homeworkId);

  long countByHomeworkIdAndAcknowledgedAtIsNotNull(UUID homeworkId);

  /**
   * Parent feed: the calling parent's recipient rows for one child, newest first. The index on
   * {@code (school_id, parent_user_id, created_at desc)} makes this a fast index scan.
   */
  @Query(
      "select r from HomeworkRecipient r "
          + "where r.parentUserId = :parentUserId and r.studentId = :studentId "
          + "order by r.createdAt desc")
  Page<HomeworkRecipient> findFeedForParentAndStudent(
      @Param("parentUserId") UUID parentUserId,
      @Param("studentId") UUID studentId,
      Pageable pageable);

  /** Returns the single recipient row a parent has for one homework item and one student. */
  Optional<HomeworkRecipient> findByHomeworkIdAndParentUserIdAndStudentId(
      UUID homeworkId, UUID parentUserId, UUID studentId);

  /** First recipient row a parent has for a homework item (any child). Used for acknowledge. */
  Optional<HomeworkRecipient> findFirstByHomeworkIdAndParentUserId(
      UUID homeworkId, UUID parentUserId);

  /** Released by {@code HomeworkReminderSweeper} once the per-school quiet window has closed. */
  @Query(
      "select r from HomeworkRecipient r "
          + "where r.deliveryStatus = com.schoolbridge.api.homework.HomeworkDeliveryStatus.DEFERRED "
          + "  and r.deferredUntil <= :now "
          + "  and r.messageId is null")
  List<HomeworkRecipient> findDeferredReadyToDispatch(@Param("now") Instant now);

  /** Webhook lookup â€” see class doc. */
  @Query("select r from HomeworkRecipient r where r.messageId = :messageId")
  Optional<HomeworkRecipient> findByMessageId(@Param("messageId") String messageId);
}
