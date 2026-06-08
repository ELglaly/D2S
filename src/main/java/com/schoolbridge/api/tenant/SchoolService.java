package com.schoolbridge.api.tenant;

import com.schoolbridge.api.tenant.dto.CreateSchoolRequest;
import com.schoolbridge.api.tenant.dto.SchoolResponse;
import com.schoolbridge.api.tenant.dto.SchoolSettingsRequest;
import com.schoolbridge.api.tenant.dto.SchoolSettingsResponse;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SchoolService {

  /** Provisions a new school; writes audit + outbox + domain event after commit. */
  SchoolResponse create(CreateSchoolRequest request);

  /** Returns a school by id or throws NotFoundException. */
  SchoolResponse findById(UUID id);

  /** Pages schools, optionally filtered by status (null = no filter). */
  Page<SchoolResponse> list(SchoolStatus statusFilter, Pageable pageable);

  SchoolSettingsResponse getSettings(UUID id);

  /** Replaces a school's settings; emits a settings-updated event. */
  SchoolSettingsResponse updateSettings(UUID id, SchoolSettingsRequest request);

  /** Suspends a school; 409 if already suspended. */
  void suspend(UUID id);

  /** Reactivates a suspended school; 409 if already active. */
  void reactivate(UUID id);
}
