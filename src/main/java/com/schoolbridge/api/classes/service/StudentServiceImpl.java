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
import com.schoolbridge.api.common.i18n.MessageResolver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentServiceImpl implements StudentService {

  private static final String AGGREGATE_TYPE = "Student";

  private static final List<String> CSV_HEADERS =
      List.of("externalId", "fullName", "dateOfBirth", "className");

  private final StudentRepository studentRepository;
  private final SchoolClassRepository classRepository;
  private final EnrollmentRepository enrollmentRepository;
  private final ClassesMapper mapper;
  private final AuditService auditService;
  private final MessageResolver messages;

  public StudentServiceImpl(
      StudentRepository studentRepository,
      SchoolClassRepository classRepository,
      EnrollmentRepository enrollmentRepository,
      ClassesMapper mapper,
      AuditService auditService,
      MessageResolver messages) {
    this.studentRepository = studentRepository;
    this.classRepository = classRepository;
    this.enrollmentRepository = enrollmentRepository;
    this.mapper = mapper;
    this.auditService = auditService;
    this.messages = messages;
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
    Set<String> seenExternalIds = new LinkedHashSet<>();
    int imported = 0;

    try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(csvStream, StandardCharsets.UTF_8));
        CSVParser parser =
            CSVFormat.RFC4180
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader)) {
      if (!CSV_HEADERS.equals(new ArrayList<>(parser.getHeaderMap().keySet()))) {
        return rejectedHeader();
      }
      for (CSVRecord record : parser) {
        int rowNumber = Math.toIntExact(record.getRecordNumber() + 1);
        if (record.size() == 1 && record.get(0).trim().isEmpty()) {
          continue;
        }
        if (record.size() != CSV_HEADERS.size()) {
          rejectedRows.add(rejected(rowNumber, "error.bulk_import.invalid_column_count"));
          continue;
        }

        String externalId = record.get("externalId").trim();
        String fullName = record.get("fullName").trim();
        String dobRaw = record.get("dateOfBirth").trim();
        String className = record.get("className").trim();

        if (fullName.isEmpty()) {
          rejectedRows.add(rejected(rowNumber, "error.bulk_import.full_name_required"));
          continue;
        }

        LocalDate dateOfBirth = null;
        if (!dobRaw.isEmpty()) {
          try {
            dateOfBirth = LocalDate.parse(dobRaw);
          } catch (DateTimeParseException ex) {
            rejectedRows.add(rejected(rowNumber, "error.bulk_import.invalid_date_of_birth"));
            continue;
          }
        }

        String extIdValue = externalId.isEmpty() ? null : externalId;
        if (extIdValue != null
            && (!seenExternalIds.add(extIdValue)
                || studentRepository.existsByExternalId(extIdValue))) {
          rejectedRows.add(
              rejected(rowNumber, "error.bulk_import.duplicate_external_id", extIdValue));
          continue;
        }

        UUID classId = null;
        if (!className.isEmpty()) {
          var found = classRepository.findByName(className);
          if (found.isEmpty()) {
            rejectedRows.add(rejected(rowNumber, "error.bulk_import.class_not_found", className));
            continue;
          }
          classId = found.get().getId();
        }

        Student student = new Student(schoolId, fullName, dateOfBirth, extIdValue);
        Student saved = studentRepository.save(student);
        imported++;

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
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid CSV stream", ex);
    }

    return new BulkImportResult(imported, rejectedRows.size(), rejectedRows);
  }

  private BulkImportResult rejectedHeader() {
    return new BulkImportResult(0, 1, List.of(rejected(1, "error.bulk_import.invalid_header")));
  }

  private RejectedRow rejected(int rowNumber, String detailKey, Object... detailArgs) {
    return new RejectedRow(
        rowNumber,
        messages.get(
            "error.bulk_import.row_invalid", rowNumber, messages.get(detailKey, detailArgs)));
  }

  Student requireStudent(UUID id) {
    return studentRepository
        .findById(id)
        .orElseThrow(() -> new NotFoundException("error.student.not_found", id));
  }
}
