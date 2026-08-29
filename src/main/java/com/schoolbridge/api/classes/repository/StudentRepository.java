package com.schoolbridge.api.classes.repository;

import com.schoolbridge.api.classes.entity.Student;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link Student}.
 *
 * <p><b>Important:</b> {@link #findById} is overridden with an explicit JPQL query so the Hibernate
 * {@code tenantFilter} applies. See {@code UserRepository} for full rationale.
 */
public interface StudentRepository extends JpaRepository<Student, UUID> {

  @Override
  @Query("select s from Student s where s.id = :id")
  Optional<Student> findById(@Param("id") UUID id);

  Page<Student> findAll(Pageable pageable);

  Optional<Student> findByExternalId(String externalId);

  boolean existsByExternalId(String externalId);
}

