package com.schoolbridge.api.subjects.service;

import com.schoolbridge.api.subjects.dto.AssignSubjectToClassRequest;
import com.schoolbridge.api.subjects.dto.ClassSubjectResponse;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ClassSubjectService {

  ClassSubjectResponse assign(UUID classId, AssignSubjectToClassRequest request);

  Page<ClassSubjectResponse> listByClass(UUID classId, Pageable pageable);

  void delete(UUID classSubjectId);
}

