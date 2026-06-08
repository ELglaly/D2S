package com.schoolbridge.api.tenant;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolRepository extends JpaRepository<School, UUID> {

  Page<School> findByStatus(SchoolStatus status, Pageable pageable);
}
