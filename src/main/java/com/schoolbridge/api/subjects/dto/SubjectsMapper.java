package com.schoolbridge.api.subjects.dto;

import com.schoolbridge.api.subjects.ClassSubject;
import com.schoolbridge.api.subjects.Subject;
import com.schoolbridge.api.subjects.TeacherSubjectAssignment;
import org.springframework.stereotype.Component;

@Component
public class SubjectsMapper {

  public SubjectResponse toResponse(Subject s) {
    return new SubjectResponse(
        s.getId(),
        s.getSchoolId(),
        s.getName(),
        s.getCode(),
        s.getDescription(),
        s.getStatus(),
        s.getCreatedAt(),
        s.getUpdatedAt());
  }

  public ClassSubjectResponse toClassSubjectResponse(ClassSubject cs, Subject subject) {
    return new ClassSubjectResponse(
        cs.getId(),
        cs.getSchoolId(),
        cs.getClassId(),
        cs.getSubjectId(),
        subject.getName(),
        subject.getCode(),
        cs.getCreatedAt());
  }

  public TeacherSubjectAssignmentResponse toAssignmentResponse(TeacherSubjectAssignment a) {
    return new TeacherSubjectAssignmentResponse(
        a.getId(),
        a.getSchoolId(),
        a.getTeacherUserId(),
        a.getClassId(),
        a.getSubjectId(),
        a.getCreatedAt());
  }
}
