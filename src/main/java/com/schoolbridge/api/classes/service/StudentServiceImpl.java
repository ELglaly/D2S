package com.schoolbridge.api.classes.service;

import com.schoolbridge.api.classes.dto.BulkImportResult;
import com.schoolbridge.api.classes.dto.BulkImportResult.RejectedRow;
import com.schoolbridge.api.classes.dto.ClassesMapper;
import com.schoolbridge.api.classes.dto.CreateStudentRequest;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.classes.dto.UpdateStudentRequest;
import com.schoolbridge.api.classes.entity.Enrollment;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.ConflictException;
import com.schoolbridge.api.common.error.NotFoundException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentServiceImpl implements StudentService {

  private static final String AGGREGATE_TYPE = "Student";

  /** CSV column indices (0-based after splitting on comma). */
  private static final int COL_EXTERNAL_ID = 0;

  private static final int COL_FULL_NAME = 1;
  private static final int COL_DATE_OF_BIRTH = 2;
  private static final int COL_CLASS_NAME = 3;
  private static final int MIN_COLUMNS = 3;

  private final StudentRepository studentRepository;
  private final SchoolClassRepository classRepository;
  private final EnrollmentRepository enrollmentRepository;
  private final ClassesMapper mapper;
  private final AuditService auditService;

  public StudentServiceImpl(
      StudentRepository studentRepository,
      SchoolClassRepository classRepository,
      EnrollmentRepository enrollmentRepository,
      ClassesMapper mapper,
      AuditService auditService) {
    this.studentRepository = studentRepository;
    this.classRepository = classRepository;
    this.enrollmentRepository = enrollmentRepository;
    this.mapper = mapper;
    this.auditService = auditService;
  }

  @Override
  @Transactional
  public StudentResponse create(UUID schoolId, CreateStudentRequest request) {
    if (request.externalId() != null
        && studentRepository.existsByExternalId(request.externalId())) {
      throw new ConflictException("error.student.external_id_in_use", request.externalId());
    }
    Student student =
        new Student(
            schoolId, request.fullName().trim(), request.dateOfBirth(), request.externalId());
    Student saved = studentRepository.save(student);
    auditService.record(
        schoolId,
        null,
        "student.created",
        AGGREGATE_TYPE,
        saved.getId(),
        Map.of("externalId", String.valueOf(saved.getExternalId())));
    return mapper.toResponse(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<StudentResponse> list(UUID schoolId, Pageable pageable) {
    return studentRepository.findAll(pageable).map(mapper::toResponse);
  }

  @Override
  @Transactional(readOnly = true)
  public StudentResponse findById(UUID id) {
    return mapper.toResponse(requireStudent(id));
  }

  @Override
  @Transactional
  public StudentResponse update(UUID id, UpdateStudentRequest request) {
    Student student = requireStudent(id);
    // If externalId changes, check uniqueness
    String newExtId = request.externalId();
    if (newExtId != null
        && !newExtId.equals(student.getExternalId())
        && studentRepository.existsByExternalId(newExtId)) {
      throw new ConflictException("error.student.external_id_in_use", newExtId);
    }
    student.update(request.fullName().trim(), request.dateOfBirth(), newExtId);
    student.changeStatus(request.status());
    auditService.record(
        student.getSchoolId(), null, "student.updated", AGGREGATE_TYPE, id, Map.of());
    return mapper.toResponse(student);
  }

  @Override
  @Transactional
  public void delete(UUID id) {
    Student student = requireStudent(id);
    auditService.record(
        student.getSchoolId(), null, "student.deleted", AGGREGATE_TYPE, id, Map.of());
    studentRepository.delete(student);
  }

  @Override
  @Transactional
  public BulkImportResult bulkImport(UUID schoolId, InputStream csvStream) {
    List<RejectedRow> rejectedRows = new ArrayList<>();
    int imported = 0;
    int rowNumber = 0;

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(csvStream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        rowNumber++;
        if (rowNumber == 1) {
          continue; // skip header
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        String[] cols = trimmed.split(",", -1);
        if (cols.length < MIN_COLUMNS) {
          rejectedRows.add(
              new RejectedRow(rowNumber, "error.bulk_import.row_invalid: too few columns"));
          continue;
        }

        String externalId = cols[COL_EXTERNAL_ID].trim();
        String fullName = cols[COL_FULL_NAME].trim();
        String dobRaw = cols[COL_DATE_OF_BIRTH].trim();
        String className = cols.length > COL_CLASS_NAME ? cols[COL_CLASS_NAME].trim() : "";

        // Validate required fields
        if (fullName.isEmpty()) {
          rejectedRows.add(
              new RejectedRow(rowNumber, "error.bulk_import.row_invalid: fullName is required"));
          continue;
        }

        // Parse date (optional but must be valid if present)
        LocalDate dateOfBirth = null;
        if (!dobRaw.isEmpty()) {
          try {
            dateOfBirth = LocalDate.parse(dobRaw);
          } catch (DateTimeParseException ex) {
            rejectedRows.add(
                new RejectedRow(
                    rowNumber,
                    "error.bulk_import.row_invalid: dateOfBirth must be ISO-8601 (yyyy-MM-dd)"));
            continue;
          }
        }

        // Check externalId uniqueness
        String extIdValue = externalId.isEmpty() ? null : externalId;
        if (extIdValue != null && studentRepository.existsByExternalId(extIdValue)) {
          rejectedRows.add(
              new RejectedRow(
                  rowNumber, "error.bulk_import.row_invalid: duplicate externalId " + extIdValue));
          continue;
        }

        // Resolve optional class
        UUID classId = null;
        if (!className.isEmpty()) {
          var found = classRepository.findByName(className);
          if (found.isEmpty()) {
            rejectedRows.add(
                new RejectedRow(
                    rowNumber, "error.bulk_import.row_invalid: class not found: " + className));
            continue;
          }
          classId = found.get().getId();
        }

        // Persist student
        Student student = new Student(schoolId, fullName, dateOfBirth, extIdValue);
        Student saved = studentRepository.save(student);
        imported++;

        // Enroll if class was specified
        if (classId != null) {
          enrollmentRepository.save(new Enrollment(schoolId, saved.getId(), classId));
        }

        auditService.record(
            schoolId,
            null,
            "student.bulk_created",
            AGGREGATE_TYPE,
            saved.getId(),
            Map.of("externalId", String.valueOf(saved.getExternalId())));
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to read CSV stream", ex);
    }

    return new BulkImportResult(imported, rejectedRows.size(), rejectedRows);
  }

  Student requireStudent(UUID id) {
    return studentRepository
        .findById(id)
        .orElseThrow(() -> new NotFoundException("error.student.not_found", id));
  }
}

