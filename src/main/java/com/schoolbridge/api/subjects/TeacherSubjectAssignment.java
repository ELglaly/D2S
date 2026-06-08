package com.schoolbridge.api.subjects;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "teacher_subject_assignments")
public class TeacherSubjectAssignment extends TenantEntity {

  @Column(name = "teacher_user_id", nullable = false, updatable = false)
  private UUID teacherUserId;

  @Column(name = "class_id", nullable = false, updatable = false)
  private UUID classId;

  @Column(name = "subject_id", nullable = false, updatable = false)
  private UUID subjectId;

  protected TeacherSubjectAssignment() {}

  public TeacherSubjectAssignment(UUID schoolId, UUID teacherUserId, UUID classId, UUID subjectId) {
    super(schoolId);
    this.teacherUserId = teacherUserId;
    this.classId = classId;
    this.subjectId = subjectId;
  }

  public UUID getTeacherUserId() {
    return teacherUserId;
  }

  public UUID getClassId() {
    return classId;
  }

  public UUID getSubjectId() {
    return subjectId;
  }
}
