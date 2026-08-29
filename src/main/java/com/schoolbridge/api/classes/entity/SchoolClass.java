package com.schoolbridge.api.classes.entity;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A class (or section) within a school for a given academic year. The homeroom teacher is a soft
 * reference â€” it is nullable because a class may not have a homeroom teacher assigned yet.
 */
@Entity
@Table(name = "school_classes")
public class SchoolClass extends TenantEntity {

  @Column(nullable = false, length = 255)
  private String name;

  @Column(name = "grade_level", nullable = false, length = 100)
  private String gradeLevel;

  @Column(name = "academic_year", nullable = false, length = 20)
  private String academicYear;

  @Column(name = "homeroom_teacher_id")
  private UUID homeroomTeacherId;

  protected SchoolClass() {}

  public SchoolClass(
      UUID schoolId, String name, String gradeLevel, String academicYear, UUID homeroomTeacherId) {
    super(schoolId);
    this.name = name;
    this.gradeLevel = gradeLevel;
    this.academicYear = academicYear;
    this.homeroomTeacherId = homeroomTeacherId;
  }

  /** Replaces all mutable fields in one call to keep service code clean. */
  public void update(String name, String gradeLevel, String academicYear, UUID homeroomTeacherId) {
    this.name = name;
    this.gradeLevel = gradeLevel;
    this.academicYear = academicYear;
    this.homeroomTeacherId = homeroomTeacherId;
  }

  public String getName() {
    return name;
  }

  public String getGradeLevel() {
    return gradeLevel;
  }

  public String getAcademicYear() {
    return academicYear;
  }

  public UUID getHomeroomTeacherId() {
    return homeroomTeacherId;
  }
}

