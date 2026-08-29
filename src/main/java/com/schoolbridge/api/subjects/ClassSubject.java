package com.schoolbridge.api.subjects;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "class_subjects")
public class ClassSubject extends TenantEntity {

  @Column(name = "class_id", nullable = false, updatable = false)
  private UUID classId;

  @Column(name = "subject_id", nullable = false, updatable = false)
  private UUID subjectId;

  protected ClassSubject() {}

  public ClassSubject(UUID schoolId, UUID classId, UUID subjectId) {
    super(schoolId);
    this.classId = classId;
    this.subjectId = subjectId;
  }

  public UUID getClassId() {
    return classId;
  }

  public UUID getSubjectId() {
    return subjectId;
  }
}

