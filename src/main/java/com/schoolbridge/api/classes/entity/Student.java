package com.schoolbridge.api.classes.entity;

import com.schoolbridge.api.classes.StudentStatus;
import com.schoolbridge.api.common.crypto.AesGcmAttributeConverter;
import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Tenant-scoped student profile. {@code fullName} is encrypted at rest with AES-256-GCM via {@link
 * AesGcmAttributeConverter}. {@code externalId} is the school's own identifier for the student
 * (e.g. from a SIS) and is unique per school when present.
 */
@Entity
@Table(name = "students")
public class Student extends TenantEntity {

  // @Convert(converter = AesGcmAttributeConverter.class)
  @Column(name = "full_name", nullable = false, length = 1024)
  private String fullName;

  @Column(name = "date_of_birth")
  private LocalDate dateOfBirth;

  @Column(name = "external_id", length = 100)
  private String externalId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private StudentStatus status;

  protected Student() {}

  public Student(UUID schoolId, String fullName, LocalDate dateOfBirth, String externalId) {
    super(schoolId);
    this.fullName = fullName;
    this.dateOfBirth = dateOfBirth;
    this.externalId = externalId;
    this.status = StudentStatus.ACTIVE;
  }

  public void update(String fullName, LocalDate dateOfBirth, String externalId) {
    this.fullName = fullName;
    this.dateOfBirth = dateOfBirth;
    this.externalId = externalId;
  }

  public void changeStatus(StudentStatus newStatus) {
    this.status = newStatus;
  }

  public String getFullName() {
    return fullName;
  }

  public LocalDate getDateOfBirth() {
    return dateOfBirth;
  }

  public String getExternalId() {
    return externalId;
  }

  public StudentStatus getStatus() {
    return status;
  }
}
