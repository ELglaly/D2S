package com.schoolbridge.api.subjects;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "subjects")
public class Subject extends TenantEntity {

  @Column(nullable = false, length = 255)
  private String name;

  @Column(length = 50)
  private String code;

  @Column(length = 1024)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private SubjectStatus status;

  protected Subject() {}

  public Subject(UUID schoolId, String name, String code, String description) {
    super(schoolId);
    this.name = name;
    this.code = code;
    this.description = description;
    this.status = SubjectStatus.ACTIVE;
  }

  public void update(String name, String code, String description, SubjectStatus status) {
    this.name = name;
    this.code = code;
    this.description = description;
    this.status = status;
  }

  public String getName() {
    return name;
  }

  public String getCode() {
    return code;
  }

  public String getDescription() {
    return description;
  }

  public SubjectStatus getStatus() {
    return status;
  }
}
