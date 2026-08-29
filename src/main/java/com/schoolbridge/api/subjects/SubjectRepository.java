package com.schoolbridge.api.subjects;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link Subject}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL because Hibernate's {@code @Filter} does
 * NOT apply to {@code EntityManager.find()} (direct primary-key lookups).
 */
public interface SubjectRepository extends JpaRepository<Subject, UUID> {

  @Override
  @Query("select s from Subject s where s.id = :id")
  Optional<Subject> findById(@Param("id") UUID id);

  Page<Subject> findAll(Pageable pageable);

  boolean existsByNameAndSchoolId(String name, UUID schoolId);
}

