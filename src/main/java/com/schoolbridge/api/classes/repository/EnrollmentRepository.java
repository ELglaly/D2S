package com.schoolbridge.api.classes.repository;

import com.schoolbridge.api.classes.entity.Enrollment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link Enrollment}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL â€” see {@code UserRepository} for rationale.
 */
public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

  @Override
  @Query("select e from Enrollment e where e.id = :id")
  Optional<Enrollment> findById(@Param("id") UUID id);

  List<Enrollment> findAllByClassId(UUID classId);

  List<Enrollment> findAllByStudentId(UUID studentId);

  boolean existsByStudentIdAndClassId(UUID studentId, UUID classId);
}

