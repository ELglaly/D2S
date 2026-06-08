package com.schoolbridge.api.classes.service;

import com.schoolbridge.api.classes.dto.CreateParentLinkRequest;
import com.schoolbridge.api.classes.dto.ParentStudentLinkResponse;
import java.util.List;
import java.util.UUID;

public interface ParentStudentLinkService {

  ParentStudentLinkResponse create(UUID schoolId, CreateParentLinkRequest request);

  List<ParentStudentLinkResponse> listByStudent(UUID studentId);

  void delete(UUID linkId);
}
