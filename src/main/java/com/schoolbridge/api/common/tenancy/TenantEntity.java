package com.schoolbridge.api.common.tenancy;

import com.schoolbridge.api.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * Base for every tenant-owned aggregate. Declares the {@code tenantFilter} Hibernate filter so
 * reads are automatically scoped to the current school.
 *
 * <p>NOTE: the filter is <em>declared</em> here but must be <em>activated</em> per session. That
 * activation (plus the mandatory cross-tenant isolation tests and a guard that fails the build if a
 * tenant entity is missing this filter) is implemented in the dedicated tenancy step before any
 * tenant entity ships.
 */
@MappedSuperclass
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "schoolId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "school_id = :schoolId")
public abstract class TenantEntity extends BaseEntity {

  @Column(name = "school_id", nullable = false, updatable = false)
  private UUID schoolId;

  protected TenantEntity() {}

  protected TenantEntity(UUID schoolId) {
    this.schoolId = schoolId;
  }

  public UUID getSchoolId() {
    return schoolId;
  }

  protected void assignSchool(UUID schoolId) {
    this.schoolId = schoolId;
  }
}

