package com.schoolbridge.api.classes.service;

import com.schoolbridge.api.classes.dto.ClassesMapper;
import com.schoolbridge.api.classes.dto.CreateParentLinkRequest;
import com.schoolbridge.api.classes.dto.ParentStudentLinkResponse;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.ConflictException;
import com.schoolbridge.api.common.error.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParentStudentLinkServiceImpl implements ParentStudentLinkService {

  private static final String AGGREGATE_TYPE = "ParentStudentLink";

  private final ParentStudentLinkRepository linkRepository;
  private final StudentServiceImpl studentService;
  private final ClassesMapper mapper;
  private final AuditService auditService;

  public ParentStudentLinkServiceImpl(
      ParentStudentLinkRepository linkRepository,
      StudentServiceImpl studentService,
      ClassesMapper mapper,
      AuditService auditService) {
    this.linkRepository = linkRepository;
    this.studentService = studentService;
    this.mapper = mapper;
    this.auditService = auditService;
  }

  @Override
  @Transactional
  public ParentStudentLinkResponse create(UUID schoolId, CreateParentLinkRequest request) {
    // Verify student exists and belongs to the same school
    studentService.requireStudent(request.studentId());

    if (linkRepository.existsByParentUserIdAndStudentId(
        request.parentUserId(), request.studentId())) {
      throw new ConflictException("error.parent_link.duplicate");
    }

    ParentStudentLink saved =
        linkRepository.save(
            new ParentStudentLink(
                schoolId,
                request.parentUserId(),
                request.studentId(),
                request.relationship(),
                request.primaryContact()));
    auditService.record(
        schoolId,
        null,
        "parent_link.created",
        AGGREGATE_TYPE,
        saved.getId(),
        Map.of("parentUserId", request.parentUserId(), "studentId", request.studentId()));
    return mapper.toResponse(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ParentStudentLinkResponse> listByStudent(UUID studentId) {
    return linkRepository.findAllByStudentId(studentId).stream().map(mapper::toResponse).toList();
  }

  @Override
  @Transactional
  public void delete(UUID linkId) {
    ParentStudentLink link =
        linkRepository.findById(linkId).orElseThrow(() -> new NotFoundException("error.not_found"));
    auditService.record(
        link.getSchoolId(), null, "parent_link.deleted", AGGREGATE_TYPE, linkId, Map.of());
    linkRepository.delete(link);
  }
}
