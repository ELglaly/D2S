package com.schoolbridge.api.grades;

import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.ConflictException;
import com.schoolbridge.api.common.error.NotFoundException;
import com.schoolbridge.api.grades.dto.CreateGradeRequest;
import com.schoolbridge.api.grades.dto.GradeRecordResponse;
import com.schoolbridge.api.grades.dto.GradesMapper;
import com.schoolbridge.api.grades.dto.UpdateGradeRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GradeServiceImpl implements GradeService {

  private static final String AGGREGATE_TYPE = "GradeRecord";

  private final GradeRecordRepository gradeRecords;
  private final StudentRepository students;
  private final SchoolClassRepository classes;
  private final GradesMapper mapper;
  private final AuditService auditService;

  public GradeServiceImpl(
      GradeRecordRepository gradeRecords,
      StudentRepository students,
      SchoolClassRepository classes,
      GradesMapper mapper,
      AuditService auditService) {
    this.gradeRecords = gradeRecords;
    this.students = students;
    this.classes = classes;
    this.mapper = mapper;
    this.auditService = auditService;
  }

  @Override
  @Transactional
  public GradeRecordResponse create(UUID schoolId, UUID actorId, CreateGradeRequest request) {
    students
        .findById(request.studentId())
        .orElseThrow(() -> new NotFoundException("error.student.not_found", request.studentId()));
    classes
        .findById(request.classId())
        .orElseThrow(() -> new NotFoundException("error.class.not_found", request.classId()));

    gradeRecords
        .findByStudentIdAndClassIdAndSubjectAndPeriod(
            request.studentId(), request.classId(), request.subject(), request.period())
        .ifPresent(
            existing -> {
              throw new ConflictException("error.grade.duplicate", existing.getId());
            });

    GradeRecord record =
        new GradeRecord(
            schoolId,
            request.studentId(),
            request.classId(),
            request.subject(),
            request.period(),
            request.score(),
            request.gradeLabel(),
            actorId,
            Instant.now(),
            request.notes());
    GradeRecord saved = gradeRecords.save(record);

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("studentId", request.studentId().toString());
    metadata.put("classId", request.classId().toString());
    metadata.put("subject", request.subject());
    metadata.put("period", request.period());
    auditService.record(
        schoolId, actorId, "grade.created", AGGREGATE_TYPE, saved.getId(), metadata);

    return mapper.toResponse(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public List<GradeRecordResponse> listByStudent(UUID studentId) {
    return gradeRecords.findAllByStudentId(studentId).stream().map(mapper::toResponse).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Page<GradeRecordResponse> listByClass(UUID classId, Pageable pageable) {
    return gradeRecords.findAllByClassId(classId, pageable).map(mapper::toResponse);
  }

  @Override
  @Transactional(readOnly = true)
  public GradeRecordResponse findById(UUID id) {
    return mapper.toResponse(requireGrade(id));
  }

  @Override
  @Transactional
  public GradeRecordResponse update(UUID id, UUID actorId, UpdateGradeRequest request) {
    GradeRecord record = requireGrade(id);
    record.update(request.score(), request.gradeLabel(), actorId, Instant.now(), request.notes());

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("studentId", record.getStudentId().toString());
    metadata.put("classId", record.getClassId().toString());
    auditService.record(
        record.getSchoolId(), actorId, "grade.updated", AGGREGATE_TYPE, record.getId(), metadata);

    return mapper.toResponse(record);
  }

  @Override
  @Transactional
  public void delete(UUID id) {
    GradeRecord record = requireGrade(id);
    gradeRecords.delete(record);
    auditService.record(
        record.getSchoolId(), null, "grade.deleted", AGGREGATE_TYPE, record.getId(), null);
  }

  private GradeRecord requireGrade(UUID id) {
    return gradeRecords
        .findById(id)
        .orElseThrow(() -> new NotFoundException("error.grade.not_found", id));
  }
}
