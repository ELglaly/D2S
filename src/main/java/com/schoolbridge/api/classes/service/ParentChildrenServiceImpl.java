package com.schoolbridge.api.classes.service;

import com.schoolbridge.api.classes.dto.ChildClassSummary;
import com.schoolbridge.api.classes.dto.ClassesMapper;
import com.schoolbridge.api.classes.dto.ParentChildResponse;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class ParentChildrenServiceImpl implements ParentChildrenService {

  private final ParentStudentLinkRepository linkRepository;
  private final EnrollmentRepository enrollmentRepository;
  private final SchoolClassRepository classRepository;
  private final ClassesMapper mapper;

  ParentChildrenServiceImpl(
      ParentStudentLinkRepository linkRepository,
      EnrollmentRepository enrollmentRepository,
      SchoolClassRepository classRepository,
      ClassesMapper mapper) {
    this.linkRepository = linkRepository;
    this.enrollmentRepository = enrollmentRepository;
    this.classRepository = classRepository;
    this.mapper = mapper;
  }

  @Override
  public List<ParentChildResponse> listChildren(UUID parentUserId) {
    return linkRepository.findLinksWithStudentsByParentUserId(parentUserId).stream()
        .map(
            row -> {
              ParentStudentLink link = (ParentStudentLink) row[0];
              Student student = (Student) row[1];
              return mapper.toChildResponse(link, student, resolveClasses(student));
            })
        .toList();
  }

  private List<ChildClassSummary> resolveClasses(Student student) {
    return enrollmentRepository.findAllByStudentId(student.getId()).stream()
        .flatMap(e -> classRepository.findById(e.getClassId()).stream())
        .map(mapper::toChildClassSummary)
        .toList();
  }
}

