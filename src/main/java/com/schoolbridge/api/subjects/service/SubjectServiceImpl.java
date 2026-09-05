package com.schoolbridge.api.subjects.service;

import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.ConflictException;
import com.schoolbridge.api.common.error.NotFoundException;
import com.schoolbridge.api.subjects.Subject;
import com.schoolbridge.api.subjects.SubjectRepository;
import com.schoolbridge.api.subjects.dto.CreateSubjectRequest;
import com.schoolbridge.api.subjects.dto.SubjectResponse;
import com.schoolbridge.api.subjects.dto.SubjectsMapper;
import com.schoolbridge.api.subjects.dto.UpdateSubjectRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubjectServiceImpl implements SubjectService {

  private static final String AGGREGATE_TYPE = "Subject";

  private final SubjectRepository subjects;
  private final SubjectsMapper mapper;
  private final AuditService auditService;

  public SubjectServiceImpl(
      SubjectRepository subjects, SubjectsMapper mapper, AuditService auditService) {
    this.subjects = subjects;
    this.mapper = mapper;
    this.auditService = auditService;
  }

  @Override
  @Transactional
  public SubjectResponse create(UUID schoolId, CreateSubjectRequest request) {
    if (subjects.existsByNameAndSchoolId(request.name().trim(), schoolId)) {
      throw new ConflictException("error.subject.duplicate");
    }
    Subject subject =
        new Subject(
            schoolId,
            request.name().trim(),
            trimOrNull(request.code()),
            trimOrNull(request.description()));
    Subject saved = subjects.save(subject);

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("name", saved.getName());
    auditService.record(schoolId, null, "subject.created", AGGREGATE_TYPE, saved.getId(), metadata);

    return mapper.toResponse(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SubjectResponse> list(UUID schoolId, Pageable pageable) {
    return subjects.findAll(pageable).map(mapper::toResponse);
  }

  @Override
  @Transactional(readOnly = true)
  public SubjectResponse findById(UUID id) {
    return mapper.toResponse(requireSubject(id));
  }

  @Override
  @Transactional
  public SubjectResponse update(UUID id, UpdateSubjectRequest request) {
    Subject subject = requireSubject(id);
    String newName = request.name().trim();
    if (!subject.getName().equals(newName)
        && subjects.existsByNameAndSchoolId(newName, subject.getSchoolId())) {
      throw new ConflictException("error.subject.duplicate");
    }
    subject.update(
        newName, trimOrNull(request.code()), trimOrNull(request.description()), request.status());

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("name", subject.getName());
    auditService.record(
        subject.getSchoolId(), null, "subject.updated", AGGREGATE_TYPE, id, metadata);

    return mapper.toResponse(subject);
  }

  @Override
  @Transactional
  public void delete(UUID id) {
    Subject subject = requireSubject(id);
    auditService.record(subject.getSchoolId(), null, "subject.deleted", AGGREGATE_TYPE, id, null);
    subjects.delete(subject);
  }

  Subject requireSubject(UUID id) {
    return subjects
        .findById(id)
        .orElseThrow(() -> new NotFoundException("error.subject.not_found", id));
  }

  private static String trimOrNull(String value) {
    return value == null ? null : value.trim();
  }
}
