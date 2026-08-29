package com.schoolbridge.api.classes.service;

import com.schoolbridge.api.classes.dto.ClassesMapper;
import com.schoolbridge.api.classes.dto.CreateSchoolClassRequest;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.dto.UpdateSchoolClassRequest;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.NotFoundException;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchoolClassServiceImpl implements SchoolClassService {

  private static final String AGGREGATE_TYPE = "SchoolClass";

  private final SchoolClassRepository repository;
  private final ClassesMapper mapper;
  private final AuditService auditService;

  public SchoolClassServiceImpl(
      SchoolClassRepository repository, ClassesMapper mapper, AuditService auditService) {
    this.repository = repository;
    this.mapper = mapper;
    this.auditService = auditService;
  }

  @Override
  @Transactional
  public SchoolClassResponse create(UUID schoolId, CreateSchoolClassRequest request) {
    SchoolClass schoolClass =
        new SchoolClass(
            schoolId,
            request.name().trim(),
            request.gradeLevel().trim(),
            request.academicYear().trim(),
            request.homeroomTeacherId());
    SchoolClass saved = repository.save(schoolClass);
    auditService.record(
        schoolId,
        null,
        "class.created",
        AGGREGATE_TYPE,
        saved.getId(),
        Map.of("name", saved.getName()));
    return mapper.toResponse(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SchoolClassResponse> list(UUID schoolId, Pageable pageable) {
    return repository.findAll(pageable).map(mapper::toResponse);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SchoolClassResponse> listByHomeroomTeacher(
      UUID schoolId, UUID teacherId, Pageable pageable) {
    return repository.findAllByHomeroomTeacherId(teacherId, pageable).map(mapper::toResponse);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SchoolClassResponse> listMyClasses(UUID teacherUserId, Pageable pageable) {
    return repository.findAllByTeacher(teacherUserId, pageable).map(mapper::toResponse);
  }

  @Override
  @Transactional(readOnly = true)
  public SchoolClassResponse findById(UUID id) {
    return mapper.toResponse(requireClass(id));
  }

  @Override
  @Transactional
  public SchoolClassResponse update(UUID id, UpdateSchoolClassRequest request) {
    SchoolClass schoolClass = requireClass(id);
    schoolClass.update(
        request.name().trim(),
        request.gradeLevel().trim(),
        request.academicYear().trim(),
        request.homeroomTeacherId());
    auditService.record(
        schoolClass.getSchoolId(), null, "class.updated", AGGREGATE_TYPE, id, Map.of());
    return mapper.toResponse(schoolClass);
  }

  @Override
  @Transactional
  public void delete(UUID id) {
    SchoolClass schoolClass = requireClass(id);
    auditService.record(
        schoolClass.getSchoolId(), null, "class.deleted", AGGREGATE_TYPE, id, Map.of());
    repository.delete(schoolClass);
  }

  SchoolClass requireClass(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new NotFoundException("error.class.not_found", id));
  }
}

