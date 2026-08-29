package com.schoolbridge.api.subjects;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link ClassSubject}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL because Hibernate's {@code @Filter} does
 * NOT apply to {@code EntityManager.find()} (direct primary-key lookups).
 */
public interface ClassSubjectRepository extends JpaRepository<ClassSubject, UUID> {

  @Override
  @Query("select cs from ClassSubject cs where cs.id = :id")
  Optional<ClassSubject> findById(@Param("id") UUID id);

  List<ClassSubject> findAllByClassId(UUID classId);

  Page<ClassSubject> findAllByClassId(UUID classId, Pageable pageable);

  /** Returns [ClassSubject, Subject] pairs for a class in a single join query. */
  @Query(
      value =
          "select cs, s from ClassSubject cs"
              + " join Subject s on s.id = cs.subjectId"
              + " where cs.classId = :classId",
      countQuery = "select count(cs) from ClassSubject cs where cs.classId = :classId")
  Page<Object[]> findAllWithSubjectByClassId(@Param("classId") UUID classId, Pageable pageable);

  boolean existsByClassIdAndSubjectId(UUID classId, UUID subjectId);

  boolean existsBySubjectId(UUID subjectId);
}

