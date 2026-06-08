package com.schoolbridge.api.subjects.service;

import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.ConflictException;
import com.schoolbridge.api.common.error.NotFoundException;
import com.schoolbridge.api.subjects.ClassSubject;
import com.schoolbridge.api.subjects.ClassSubjectRepository;
import com.schoolbridge.api.subjects.Subject;
import com.schoolbridge.api.subjects.TeacherSubjectAssignmentRepository;
import com.schoolbridge.api.subjects.dto.AssignSubjectToClassRequest;
import com.schoolbridge.api.subjects.dto.ClassSubjectResponse;
import com.schoolbridge.api.subjects.dto.SubjectsMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassSubjectServiceImpl implements ClassSubjectService {

  private static final String AGGREGATE_TYPE = "ClassSubject";

  private final ClassSubjectRepository classSubjects;
  private final SubjectServiceImpl subjectService;
  private final SchoolClassRepository classes;
  private final TeacherSubjectAssignmentRepository teacherAssignments;
  private final SubjectsMapper mapper;
  private final AuditService auditService;

  public ClassSubjectServiceImpl(
      ClassSubjectRepository classSubjects,
      SubjectServiceImpl subjectService,
      SchoolClassRepository classes,
      TeacherSubjectAssignmentRepository teacherAssignments,
      SubjectsMapper mapper,
      AuditService auditService) {
    this.classSubjects = classSubjects;
    this.subjectService = subjectService;
    this.classes = classes;
    this.teacherAssignments = teacherAssignments;
    this.mapper = mapper;
    this.auditService = auditService;
  }

  @Override
  @Transactional
  public ClassSubjectResponse assign(UUID classId, AssignSubjectToClassRequest request) {
    SchoolClass schoolClass =
        classes
            .findById(classId)
            .orElseThrow(() -> new NotFoundException("error.class.not_found", classId));
    Subject subject = subjectService.requireSubject(request.subjectId());

    if (classSubjects.existsByClassIdAndSubjectId(classId, request.subjectId())) {
      throw new ConflictException("error.class_subject.duplicate");
    }

    ClassSubject saved =
        classSubjects.save(
            new ClassSubject(schoolClass.getSchoolId(), classId, request.subjectId()));

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("classId", classId.toString());
    metadata.put("subjectId", request.subjectId().toString());
    auditService.record(
        schoolClass.getSchoolId(),
        null,
        "class_subject.created",
        AGGREGATE_TYPE,
        saved.getId(),
        metadata);

    return mapper.toClassSubjectResponse(saved, subject);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ClassSubjectResponse> listByClass(UUID classId, Pageable pageable) {
    return classSubjects
        .findAllWithSubjectByClassId(classId, pageable)
        .map(row -> mapper.toClassSubjectResponse((ClassSubject) row[0], (Subject) row[1]));
  }

  @Override
  @Transactional
  public void delete(UUID classSubjectId) {
    ClassSubject classSubject =
        classSubjects
            .findById(classSubjectId)
            .orElseThrow(
                () -> new NotFoundException("error.class_subject.not_found", classSubjectId));

    if (teacherAssignments.existsByClassIdAndSubjectId(
        classSubject.getClassId(), classSubject.getSubjectId())) {
      throw new ConflictException("error.class_subject.has_teacher_assignments");
    }

    auditService.record(
        classSubject.getSchoolId(),
        null,
        "class_subject.deleted",
        AGGREGATE_TYPE,
        classSubjectId,
        null);
    classSubjects.delete(classSubject);
  }
}
