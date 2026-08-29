package com.schoolbridge.api.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, UUID> {

  Optional<PlatformAdmin> findByEmail(String email);
}

