package com.schoolbridge.api.classes.service;

import com.schoolbridge.api.classes.dto.EnrollStudentRequest;
import com.schoolbridge.api.classes.dto.EnrollmentResponse;
import java.util.List;
import java.util.UUID;

public interface EnrollmentService {

  EnrollmentResponse enroll(UUID classId, EnrollStudentRequest request);

  List<EnrollmentResponse> listByClass(UUID classId);

  void delete(UUID enrollmentId);
}
