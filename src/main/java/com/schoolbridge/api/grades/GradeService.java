package com.schoolbridge.api.grades;

import com.schoolbridge.api.grades.dto.CreateGradeRequest;
import com.schoolbridge.api.grades.dto.GradeRecordResponse;
import com.schoolbridge.api.grades.dto.UpdateGradeRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface GradeService {

  GradeRecordResponse create(UUID schoolId, UUID actorId, CreateGradeRequest request);

  List<GradeRecordResponse> listByStudent(UUID studentId);

  Page<GradeRecordResponse> listByClass(UUID classId, Pageable pageable);

  GradeRecordResponse findById(UUID id);

  GradeRecordResponse update(UUID id, UUID actorId, UpdateGradeRequest request);

  void delete(UUID id);
}

