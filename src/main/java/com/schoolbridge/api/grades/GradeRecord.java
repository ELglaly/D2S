package com.schoolbridge.api.grades;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "grade_records")
public class GradeRecord extends TenantEntity {

  @Column(name = "student_id", nullable = false, updatable = false)
  private UUID studentId;

  @Column(name = "class_id", nullable = false, updatable = false)
  private UUID classId;

  @Column(nullable = false, length = 200, updatable = false)
  private String subject;

  @Column(nullable = false, length = 50, updatable = false)
  private String period;

  @Column(precision = 5, scale = 2)
  private BigDecimal score;

  @Column(name = "grade_label", length = 10)
  private String gradeLabel;

  @Column(name = "graded_by_user_id", nullable = false)
  private UUID gradedByUserId;

  @Column(name = "graded_at", nullable = false)
  private Instant gradedAt;

  // @Convert(converter = AesGcmAttributeConverter.class)
  @Column(columnDefinition = "TEXT")
  private String notes;

  protected GradeRecord() {}

  public GradeRecord(
      UUID schoolId,
      UUID studentId,
      UUID classId,
      String subject,
      String period,
      BigDecimal score,
      String gradeLabel,
      UUID gradedByUserId,
      Instant gradedAt,
      String notes) {
    super(schoolId);
    this.studentId = studentId;
    this.classId = classId;
    this.subject = subject;
    this.period = period;
    this.score = score;
    this.gradeLabel = gradeLabel;
    this.gradedByUserId = gradedByUserId;
    this.gradedAt = gradedAt;
    this.notes = notes;
  }

  public void update(
      BigDecimal score, String gradeLabel, UUID gradedByUserId, Instant gradedAt, String notes) {
    this.score = score;
    this.gradeLabel = gradeLabel;
    this.gradedByUserId = gradedByUserId;
    this.gradedAt = gradedAt;
    this.notes = notes;
  }

  public UUID getStudentId() {
    return studentId;
  }

  public UUID getClassId() {
    return classId;
  }

  public String getSubject() {
    return subject;
  }

  public String getPeriod() {
    return period;
  }

  public BigDecimal getScore() {
    return score;
  }

  public String getGradeLabel() {
    return gradeLabel;
  }

  public UUID getGradedByUserId() {
    return gradedByUserId;
  }

  public Instant getGradedAt() {
    return gradedAt;
  }

  public String getNotes() {
    return notes;
  }
}

