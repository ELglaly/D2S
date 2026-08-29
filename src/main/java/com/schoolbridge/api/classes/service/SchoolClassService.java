package com.schoolbridge.api.classes.service;

import com.schoolbridge.api.classes.dto.CreateSchoolClassRequest;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.dto.UpdateSchoolClassRequest;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SchoolClassService {

  SchoolClassResponse create(UUID schoolId, CreateSchoolClassRequest request);

  Page<SchoolClassResponse> list(UUID schoolId, Pageable pageable);

  Page<SchoolClassResponse> listByHomeroomTeacher(UUID schoolId, UUID teacherId, Pageable pageable);

  Page<SchoolClassResponse> listMyClasses(UUID teacherUserId, Pageable pageable);

  SchoolClassResponse findById(UUID id);

  SchoolClassResponse update(UUID id, UpdateSchoolClassRequest request);

  void delete(UUID id);
}

