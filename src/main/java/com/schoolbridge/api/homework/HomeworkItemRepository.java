package com.schoolbridge.api.homework;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link HomeworkItem}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL because Hibernate's {@code @Filter} does
 * NOT apply to {@code EntityManager.find()} (direct primary-key lookups). See {@code
 * UserRepository} for the canonical explanation.
 */
public interface HomeworkItemRepository extends JpaRepository<HomeworkItem, UUID> {

  @Override
  @Query("select h from HomeworkItem h where h.id = :id")
  Optional<HomeworkItem> findById(@Param("id") UUID id);

  /** Teacher list view — filtered by class, status, and date range. Caller may pass null params. */
  @Query(
      "select h from HomeworkItem h "
          + "where (:classId is null or h.classId = :classId) "
          + "  and (:status is null or h.status = :status) "
          + "  and (:dueDateFrom is null or h.dueDate >= :dueDateFrom) "
          + "  and (:dueDateTo is null or h.dueDate <= :dueDateTo) "
          + "order by h.createdAt desc")
  Page<HomeworkItem> findFiltered(
      @Param("classId") UUID classId,
      @Param("status") HomeworkStatus status,
      @Param("dueDateFrom") LocalDate dueDateFrom,
      @Param("dueDateTo") LocalDate dueDateTo,
      Pageable pageable);

  /** Items referencing this attachment. Blocks deleting an attachment that is still in use. */
  @Query("select count(h) from HomeworkItem h where h.attachmentKey = :attachmentKey")
  long countReferencingAttachment(@Param("attachmentKey") String attachmentKey);

  /** Teacher own-history query — items they authored, newest first. */
  @Query("select h from HomeworkItem h where h.teacherId = :teacherId order by h.createdAt desc")
  Page<HomeworkItem> findByTeacherId(@Param("teacherId") UUID teacherId, Pageable pageable);

  /**
   * Sweeper scan: PUBLISHED items whose dueDate is on or before {@code horizon} and whose reminder
   * has not yet been sent. The ±window check is done in Java after this query returns.
   */
  @Query(
      "select h from HomeworkItem h "
          + "where h.status = com.schoolbridge.api.homework.HomeworkStatus.PUBLISHED "
          + "  and h.dueDate >= :today "
          + "  and h.dueDate <= :horizon "
          + "  and h.reminderSentAt is null")
  List<HomeworkItem> findPublishedDueForReminder(
      @Param("today") LocalDate today, @Param("horizon") LocalDate horizon);

  /** Check whether a parent has any recipient row for a given homework item. */
  @Query(
      "select count(r) > 0 from HomeworkRecipient r "
          + "where r.homeworkId = :homeworkId and r.parentUserId = :parentUserId")
  boolean existsRecipientForParent(
      @Param("homeworkId") UUID homeworkId, @Param("parentUserId") UUID parentUserId);
}
