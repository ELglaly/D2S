package com.schoolbridge.api.subjects.service;

import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.ConflictException;
import com.schoolbridge.api.common.error.NotFoundException;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.subjects.*;
import com.schoolbridge.api.subjects.dto.AssignTeacherToSubjectRequest;
import com.schoolbridge.api.subjects.dto.StudentSubjectResponse;
import com.schoolbridge.api.subjects.dto.SubjectsMapper;
import com.schoolbridge.api.subjects.dto.TeacherSubjectAssignmentResponse;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherSubjectAssignmentServiceImpl implements TeacherSubjectAssignmentService {

  private static final String AGGREGATE_TYPE = "TeacherSubjectAssignment";

  private final TeacherSubjectAssignmentRepository assignments;
  private final ClassSubjectRepository classSubjects;
  private final SchoolClassRepository classes;
  private final UserRepository users;
  private final SubjectsMapper mapper;
  private final AuditService auditService;

  public TeacherSubjectAssignmentServiceImpl(
      TeacherSubjectAssignmentRepository assignments,
      ClassSubjectRepository classSubjects,
      SchoolClassRepository classes,
      UserRepository users,
      SubjectsMapper mapper,
      AuditService auditService) {
    this.assignments = assignments;
    this.classSubjects = classSubjects;
    this.classes = classes;
    this.users = users;
    this.mapper = mapper;
    this.auditService = auditService;
  }

  @Override
  @Transactional
  public TeacherSubjectAssignmentResponse assign(
      UUID classId, UUID subjectId, AssignTeacherToSubjectRequest request) {
    SchoolClass schoolClass =
        classes
            .findById(classId)
            .orElseThrow(() -> new NotFoundException("error.class.not_found", classId));

    if (!classSubjects.existsByClassIdAndSubjectId(classId, subjectId)) {
      throw new NotFoundException("error.class_subject.not_found", subjectId);
    }

    users
        .findById(request.teacherUserId())
        .filter(u -> u.getRole() == UserRole.TEACHER)
        .orElseThrow(() -> new NotFoundException("error.user.not_found", request.teacherUserId()));

    if (assignments.existsByTeacherUserIdAndClassIdAndSubjectId(
        request.teacherUserId(), classId, subjectId)) {
      throw new ConflictException("error.teacher_subject_assignment.duplicate");
    }

    TeacherSubjectAssignment saved =
        assignments.save(
            new TeacherSubjectAssignment(
                schoolClass.getSchoolId(), request.teacherUserId(), classId, subjectId));

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("teacherUserId", request.teacherUserId().toString());
    metadata.put("classId", classId.toString());
    metadata.put("subjectId", subjectId.toString());
    auditService.record(
        schoolClass.getSchoolId(),
        null,
        "teacher_subject_assignment.created",
        AGGREGATE_TYPE,
        saved.getId(),
        metadata);

    return mapper.toAssignmentResponse(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public List<TeacherSubjectAssignmentResponse> listByClass(UUID classId) {
    return assignments.findAllByClassId(classId).stream()
        .map(mapper::toAssignmentResponse)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<TeacherSubjectAssignmentResponse> listByClassAndSubject(
      UUID classId, UUID subjectId) {
    return assignments.findAllByClassIdAndSubjectId(classId, subjectId).stream()
        .map(mapper::toAssignmentResponse)
        .toList();
  }

  @Override
  @Transactional
  public void delete(UUID assignmentId) {
    TeacherSubjectAssignment assignment =
        assignments
            .findById(assignmentId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "error.teacher_subject_assignment.not_found", assignmentId));

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("teacherUserId", assignment.getTeacherUserId().toString());
    metadata.put("classId", assignment.getClassId().toString());
    metadata.put("subjectId", assignment.getSubjectId().toString());
    auditService.record(
        assignment.getSchoolId(),
        null,
        "teacher_subject_assignment.deleted",
        AGGREGATE_TYPE,
        assignmentId,
        metadata);

    assignments.delete(assignment);
  }

  @Override
  @Transactional(readOnly = true)
  public List<StudentSubjectResponse> getSubjectsForStudent(UUID studentId) {
    return assignments.findSubjectsWithTeacherForStudent(studentId).stream()
        .map(
            row -> {
              Subject subject = (Subject) row[0];
              UUID classId = (UUID) row[1];
              UUID teacherUserId = (UUID) row[2];
              return new StudentSubjectResponse(
                  subject.getId(), subject.getName(), subject.getCode(), classId, teacherUserId);
            })
        .collect(
            Collectors.collectingAndThen(
                Collectors.toMap(
                    r -> r.classId() + ":" + r.subjectId(),
                    r -> r,
                    (first, dup) -> first,
                    LinkedHashMap::new),
                m -> List.copyOf(m.values())));
  }
}
