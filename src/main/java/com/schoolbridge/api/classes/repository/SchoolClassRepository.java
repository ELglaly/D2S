package com.schoolbridge.api.classes.repository;

import com.schoolbridge.api.classes.entity.SchoolClass;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link SchoolClass}.
 *
 * <p><b>Important:</b> {@link #findById} is overridden with an explicit JPQL query because
 * Hibernate's {@code @Filter} does NOT apply to {@code EntityManager.find()} (direct primary-key
 * lookups). See {@code UserRepository} for the canonical explanation and isolation-test template.
 */
public interface SchoolClassRepository extends JpaRepository<SchoolClass, UUID> {

  @Override
  @Query("select c from SchoolClass c where c.id = :id")
  Optional<SchoolClass> findById(@Param("id") UUID id);

  Page<SchoolClass> findAll(Pageable pageable);

  /** Returns all classes where the given user is the homeroom teacher. */
  List<SchoolClass> findAllByHomeroomTeacherId(UUID homeroomTeacherId);

  Page<SchoolClass> findAllByHomeroomTeacherId(UUID homeroomTeacherId, Pageable pageable);

  /**
   * Returns all classes for a teacher â€” homeroom or formally assigned via TeacherAssignment. Uses
   * an EXISTS subquery rather than a join to avoid duplicates when a teacher is both homeroom
   * teacher and has an assignment row for the same class.
   */
  @Query(
      "select c from SchoolClass c "
          + "where c.homeroomTeacherId = :uid "
          + "   or exists ("
          + "        select a from com.schoolbridge.api.subjects.TeacherSubjectAssignment a "
          + "        where a.classId = c.id and a.teacherUserId = :uid"
          + "   )")
  Page<SchoolClass> findAllByTeacher(@Param("uid") UUID teacherUserId, Pageable pageable);

  /** Looks up a class by its display name (used during CSV bulk import to resolve className). */
  Optional<SchoolClass> findByName(String name);

  boolean existsByNameAndAcademicYear(String name, String academicYear);

  /**
   * Returns {@code true} if the given teacher is the homeroom teacher of the class OR has at least
   * one subject-assignment row for it. Single query; use this instead of two separate calls.
   */
  @Query(
      "select count(c) > 0 from SchoolClass c "
          + "where c.id = :classId "
          + "and (c.homeroomTeacherId = :uid "
          + "     or exists ("
          + "          select a from com.schoolbridge.api.subjects.TeacherSubjectAssignment a "
          + "          where a.classId = c.id and a.teacherUserId = :uid"
          + "     ))")
  boolean existsByIdAndTeacher(@Param("classId") UUID classId, @Param("uid") UUID teacherUserId);
}

