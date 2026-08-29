package com.schoolbridge.api.subjects.service;

import com.schoolbridge.api.subjects.dto.AssignTeacherToSubjectRequest;
import com.schoolbridge.api.subjects.dto.StudentSubjectResponse;
import com.schoolbridge.api.subjects.dto.TeacherSubjectAssignmentResponse;
import java.util.List;
import java.util.UUID;

public interface TeacherSubjectAssignmentService {

  TeacherSubjectAssignmentResponse assign(
      UUID classId, UUID subjectId, AssignTeacherToSubjectRequest request);

  List<TeacherSubjectAssignmentResponse> listByClass(UUID classId);

  List<TeacherSubjectAssignmentResponse> listByClassAndSubject(UUID classId, UUID subjectId);

  void delete(UUID assignmentId);

  List<StudentSubjectResponse> getSubjectsForStudent(UUID studentId);
}

