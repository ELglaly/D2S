package com.schoolbridge.api.subjects;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link TeacherSubjectAssignment}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL because Hibernate's {@code @Filter} does
 * NOT apply to {@code EntityManager.find()} (direct primary-key lookups).
 */
public interface TeacherSubjectAssignmentRepository
    extends JpaRepository<TeacherSubjectAssignment, UUID> {

  @Override
  @Query("select a from TeacherSubjectAssignment a where a.id = :id")
  Optional<TeacherSubjectAssignment> findById(@Param("id") UUID id);

  List<TeacherSubjectAssignment> findAllByClassId(UUID classId);

  List<TeacherSubjectAssignment> findAllByClassIdAndSubjectId(UUID classId, UUID subjectId);

  boolean existsByTeacherUserIdAndClassIdAndSubjectId(
      UUID teacherUserId, UUID classId, UUID subjectId);

  boolean existsByTeacherUserIdAndClassId(UUID teacherUserId, UUID classId);

  boolean existsByClassIdAndSubjectId(UUID classId, UUID subjectId);

  /**
   * Returns [Subject, classId, teacherUserId] rows for every subject a student is enrolled in. LEFT
   * JOIN on assignments means teacherUserId may be null. Multiple rows per (classId, subjectId) are
   * possible when multiple teachers are assigned — callers must deduplicate.
   */
  @Query(
      "select s, cs.classId, a.teacherUserId"
          + " from Enrollment e"
          + " join ClassSubject cs on cs.classId = e.classId"
          + " join Subject s on s.id = cs.subjectId"
          + " left join TeacherSubjectAssignment a"
          + "   on a.classId = cs.classId and a.subjectId = cs.subjectId"
          + " where e.studentId = :studentId")
  List<Object[]> findSubjectsWithTeacherForStudent(@Param("studentId") UUID studentId);
}
