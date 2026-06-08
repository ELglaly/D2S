package com.schoolbridge.api.classes.service;

import com.schoolbridge.api.classes.dto.ClassesMapper;
import com.schoolbridge.api.classes.dto.EnrollStudentRequest;
import com.schoolbridge.api.classes.dto.EnrollmentResponse;
import com.schoolbridge.api.classes.entity.Enrollment;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.ConflictException;
import com.schoolbridge.api.common.error.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrollmentServiceImpl implements EnrollmentService {

  private static final String AGGREGATE_TYPE = "Enrollment";

  private final EnrollmentRepository enrollmentRepository;
  private final SchoolClassServiceImpl classService;
  private final StudentServiceImpl studentService;
  private final ClassesMapper mapper;
  private final AuditService auditService;

  public EnrollmentServiceImpl(
      EnrollmentRepository enrollmentRepository,
      SchoolClassServiceImpl classService,
      StudentServiceImpl studentService,
      ClassesMapper mapper,
      AuditService auditService) {
    this.enrollmentRepository = enrollmentRepository;
    this.classService = classService;
    this.studentService = studentService;
    this.mapper = mapper;
    this.auditService = auditService;
  }

  @Override
  @Transactional
  public EnrollmentResponse enroll(UUID classId, EnrollStudentRequest request) {
    SchoolClass schoolClass = classService.requireClass(classId);
    Student student = studentService.requireStudent(request.studentId());

    if (enrollmentRepository.existsByStudentIdAndClassId(student.getId(), classId)) {
      throw new ConflictException("error.enrollment.duplicate");
    }

    Enrollment saved =
        enrollmentRepository.save(
            new Enrollment(schoolClass.getSchoolId(), student.getId(), classId));
    auditService.record(
        schoolClass.getSchoolId(),
        null,
        "enrollment.created",
        AGGREGATE_TYPE,
        saved.getId(),
        Map.of("studentId", student.getId(), "classId", classId));
    return mapper.toResponse(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public List<EnrollmentResponse> listByClass(UUID classId) {
    return enrollmentRepository.findAllByClassId(classId).stream().map(mapper::toResponse).toList();
  }

  @Override
  @Transactional
  public void delete(UUID enrollmentId) {
    Enrollment enrollment =
        enrollmentRepository
            .findById(enrollmentId)
            .orElseThrow(() -> new NotFoundException("error.not_found"));
    auditService.record(
        enrollment.getSchoolId(),
        null,
        "enrollment.deleted",
        AGGREGATE_TYPE,
        enrollmentId,
        Map.of());
    enrollmentRepository.delete(enrollment);
  }
}
