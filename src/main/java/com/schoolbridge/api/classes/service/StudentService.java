package com.schoolbridge.api.classes.service;

import com.schoolbridge.api.classes.dto.BulkImportResult;
import com.schoolbridge.api.classes.dto.CreateStudentRequest;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.classes.dto.UpdateStudentRequest;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StudentService {

  StudentResponse create(UUID schoolId, CreateStudentRequest request);

  Page<StudentResponse> list(UUID schoolId, Pageable pageable);

  StudentResponse findById(UUID id);

  StudentResponse update(UUID id, UpdateStudentRequest request);

  void delete(UUID id);

  /**
   * Parses a UTF-8 CSV stream (header + data rows) and bulk-creates students, optionally enrolling
   * them into a named class. Rows that fail validation are collected and returned rather than
   * aborting the whole import.
   *
   * <p>Expected CSV columns (comma-separated, first row = header): {@code
   * externalId,fullName,dateOfBirth,className} where {@code className} is optional.
   */
  BulkImportResult bulkImport(UUID schoolId, InputStream csvStream);
}

