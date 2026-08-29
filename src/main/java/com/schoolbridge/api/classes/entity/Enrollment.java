package com.schoolbridge.api.classes.entity;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * M:N bridge between {@link Student} and {@link SchoolClass}. One student may be enrolled in
 * multiple classes (tutoring centres), hence the separate bridge rather than a single FK on
 * Student.
 */
@Entity
@Table(name = "enrollments")
public class Enrollment extends TenantEntity {

  @Column(name = "student_id", nullable = false, updatable = false)
  private UUID studentId;

  @Column(name = "class_id", nullable = false, updatable = false)
  private UUID classId;

  protected Enrollment() {}

  public Enrollment(UUID schoolId, UUID studentId, UUID classId) {
    super(schoolId);
    this.studentId = studentId;
    this.classId = classId;
  }

  public UUID getStudentId() {
    return studentId;
  }

  public UUID getClassId() {
    return classId;
  }
}

