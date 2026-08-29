package com.schoolbridge.api.classes.dto;

import com.schoolbridge.api.classes.entity.Enrollment;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.entity.Student;
import java.util.List;
import org.springframework.stereotype.Component;

/** Explicit hand-written mapper for M5 aggregates. Keeps transformations obvious at review time. */
@Component
public class ClassesMapper {

  public SchoolClassResponse toResponse(SchoolClass c) {
    return new SchoolClassResponse(
        c.getId(),
        c.getSchoolId(),
        c.getName(),
        c.getGradeLevel(),
        c.getAcademicYear(),
        c.getHomeroomTeacherId(),
        c.getCreatedAt(),
        c.getUpdatedAt());
  }

  public StudentResponse toResponse(Student s) {
    return new StudentResponse(
        s.getId(),
        s.getSchoolId(),
        s.getFullName(),
        s.getDateOfBirth(),
        s.getExternalId(),
        s.getStatus(),
        s.getCreatedAt(),
        s.getUpdatedAt());
  }

  public EnrollmentResponse toResponse(Enrollment e) {
    return new EnrollmentResponse(
        e.getId(), e.getSchoolId(), e.getStudentId(), e.getClassId(), e.getCreatedAt());
  }

  public ParentStudentLinkResponse toResponse(ParentStudentLink l) {
    return new ParentStudentLinkResponse(
        l.getId(),
        l.getSchoolId(),
        l.getParentUserId(),
        l.getStudentId(),
        l.getRelationship(),
        l.isPrimaryContact(),
        l.getCreatedAt());
  }

  public ChildClassSummary toChildClassSummary(SchoolClass c) {
    return new ChildClassSummary(c.getId(), c.getName(), c.getGradeLevel(), c.getAcademicYear());
  }

  public ParentChildResponse toChildResponse(
      ParentStudentLink link, Student student, List<ChildClassSummary> classes) {
    return new ParentChildResponse(
        student.getId(),
        student.getFullName(),
        student.getDateOfBirth(),
        student.getStatus(),
        link.getRelationship(),
        link.isPrimaryContact(),
        classes);
  }
}

