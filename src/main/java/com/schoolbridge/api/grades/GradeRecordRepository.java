package com.schoolbridge.api.grades;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link GradeRecord}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL because Hibernate's {@code @Filter} does
 * NOT apply to {@code EntityManager.find()} (direct primary-key lookups).
 */
public interface GradeRecordRepository extends JpaRepository<GradeRecord, UUID> {

  @Override
  @Query("select g from GradeRecord g where g.id = :id")
  Optional<GradeRecord> findById(@Param("id") UUID id);

  @Query(
      "select g from GradeRecord g where g.studentId = :studentId"
          + " order by g.period, g.subject")
  List<GradeRecord> findAllByStudentId(@Param("studentId") UUID studentId);

  @Query("select g from GradeRecord g where g.classId = :classId order by g.period, g.subject")
  Page<GradeRecord> findAllByClassId(@Param("classId") UUID classId, Pageable pageable);

  Optional<GradeRecord> findByStudentIdAndClassIdAndSubjectAndPeriod(
      UUID studentId, UUID classId, String subject, String period);
}

