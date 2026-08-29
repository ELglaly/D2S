package com.schoolbridge.api.classes.entity;

import com.schoolbridge.api.classes.RelationshipType;
import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Links a parent user to one of their children. {@code primaryContact} indicates the parent who
 * receives notifications when multiple parents are linked to the same student. Only one link per
 * (parentUserId, studentId) pair is allowed (enforced by the unique index in migration 005).
 */
@Entity
@Table(name = "parent_student_links")
public class ParentStudentLink extends TenantEntity {

  @Column(name = "parent_user_id", nullable = false, updatable = false)
  private UUID parentUserId;

  @Column(name = "student_id", nullable = false, updatable = false)
  private UUID studentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RelationshipType relationship;

  @Column(name = "primary_contact", nullable = false)
  private boolean primaryContact;

  protected ParentStudentLink() {}

  public ParentStudentLink(
      UUID schoolId,
      UUID parentUserId,
      UUID studentId,
      RelationshipType relationship,
      boolean primaryContact) {
    super(schoolId);
    this.parentUserId = parentUserId;
    this.studentId = studentId;
    this.relationship = relationship;
    this.primaryContact = primaryContact;
  }

  public UUID getParentUserId() {
    return parentUserId;
  }

  public UUID getStudentId() {
    return studentId;
  }

  public RelationshipType getRelationship() {
    return relationship;
  }

  public boolean isPrimaryContact() {
    return primaryContact;
  }
}

