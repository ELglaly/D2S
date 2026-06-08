package com.schoolbridge.api.classes.repository;

import com.schoolbridge.api.classes.entity.Enrollment;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link ParentStudentLink}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL — see {@code UserRepository} for rationale.
 *
 * <p>The {@code findAllForXxxAnnouncementScope} queries return all (parent, student) pairs an
 * announcement should fan out to. They are used by M6's recipient materializer; staying on this
 * repository keeps the cross-aggregate join with {@link Enrollment} colocated with the link domain.
 */
public interface ParentStudentLinkRepository extends JpaRepository<ParentStudentLink, UUID> {

  @Override
  @Query("select l from ParentStudentLink l where l.id = :id")
  Optional<ParentStudentLink> findById(@Param("id") UUID id);

  List<ParentStudentLink> findAllByStudentId(UUID studentId);

  List<ParentStudentLink> findAllByParentUserId(UUID parentUserId);

  /** Returns [ParentStudentLink, Student] pairs for a parent in a single join query. */
  @Query(
      "select l, s from ParentStudentLink l"
          + " join Student s on s.id = l.studentId"
          + " where l.parentUserId = :parentId")
  List<Object[]> findLinksWithStudentsByParentUserId(@Param("parentId") UUID parentId);

  boolean existsByParentUserIdAndStudentId(UUID parentUserId, UUID studentId);

  /** All (parent, student) pairs for every student in the current school. */
  @Query("select l from ParentStudentLink l")
  List<ParentStudentLink> findAllForSchoolAnnouncementScope();

  /** All (parent, student) pairs where the student is enrolled in a class with the given grade. */
  @Query(
      "select l from ParentStudentLink l "
          + "where l.studentId in ("
          + "  select e.studentId from Enrollment e, SchoolClass c "
          + "  where e.classId = c.id and c.gradeLevel = :gradeLevel"
          + ")")
  List<ParentStudentLink> findAllForGradeAnnouncementScope(@Param("gradeLevel") String gradeLevel);

  /** All (parent, student) pairs where the student is enrolled in the given class. */
  @Query(
      "select l from ParentStudentLink l "
          + "where l.studentId in ("
          + "  select e.studentId from Enrollment e where e.classId = :classId"
          + ")")
  List<ParentStudentLink> findAllForClassAnnouncementScope(@Param("classId") UUID classId);

  /** All (parent, student) pairs for the given explicit student ids (CUSTOM scope). */
  @Query("select l from ParentStudentLink l where l.studentId in :studentIds")
  List<ParentStudentLink> findAllByStudentIdIn(@Param("studentIds") Collection<UUID> studentIds);
}
