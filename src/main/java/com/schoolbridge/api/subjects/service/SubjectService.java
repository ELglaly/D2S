package com.schoolbridge.api.subjects.service;

import com.schoolbridge.api.subjects.dto.CreateSubjectRequest;
import com.schoolbridge.api.subjects.dto.SubjectResponse;
import com.schoolbridge.api.subjects.dto.UpdateSubjectRequest;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SubjectService {

  SubjectResponse create(UUID schoolId, CreateSubjectRequest request);

  Page<SubjectResponse> list(UUID schoolId, Pageable pageable);

  SubjectResponse findById(UUID id);

  SubjectResponse update(UUID id, UpdateSubjectRequest request);

  void delete(UUID id);
}

