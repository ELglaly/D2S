package com.schoolbridge.api.classes;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.dto.BulkImportResult;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.classes.service.StudentService;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration test for {@link StudentService#bulkImport}. Covers happy path (all rows valid with
 * class enrollment) and bad-row rejection (various error scenarios).
 */
@SpringBootTest
class BulkImportIntegrationTest extends AbstractIntegrationTest {

  @Autowired StudentService studentService;
  @Autowired StudentRepository studentRepository;
  @Autowired EnrollmentRepository enrollmentRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired UserRepository userRepository;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID classId;

  @BeforeEach
  void setUp() {
    tx.executeWithoutResult(s -> enrollmentRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "Import School",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());

    classId =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolId, "Grade 3A", "Grade 3", "2025-2026", null))
                    .getId());

    TenantContext.set(schoolId);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void happyPath_allValidRows_importsStudentsAndEnrollments() {
    String csv =
        "externalId,fullName,dateOfBirth,className\n"
            + "EXT-001,Ahmed Mohamed,2015-03-15,Grade 3A\n"
            + "EXT-002,Fatima Hassan,2014-07-22,Grade 3A\n";

    BulkImportResult result =
        tx.execute(
            s ->
                studentService.bulkImport(
                    schoolId, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))));

    assertThat(result).isNotNull();
    assertThat(result.importedCount()).isEqualTo(2);
    assertThat(result.rejectedCount()).isZero();
    assertThat(result.rejectedRows()).isEmpty();

    TenantContext.set(schoolId);
    var students = tx.execute(s -> studentRepository.findAll());
    assertThat(students).hasSize(2);

    TenantContext.set(schoolId);
    var enrollments = tx.execute(s -> enrollmentRepository.findAllByClassId(classId));
    assertThat(enrollments).hasSize(2);
  }

  @Test
  void badRows_mixedContent_importsValidAndRejectsInvalid() {
    String csv =
        "externalId,fullName,dateOfBirth,className\n"
            + "EXT-001,Ahmed Mohamed,2015-03-15,Grade 3A\n" // valid
            + "EXT-002,,2014-07-22,Grade 3A\n" // missing fullName
            + "EXT-003,Hassan Ali,not-a-date,Grade 3A\n" // bad date
            + "EXT-004,Sara Nour,2016-01-10,NonExistentClass\n" // class not found
            + "EXT-001,Duplicate ID,2017-05-20,Grade 3A\n"; // duplicate externalId

    BulkImportResult result =
        tx.execute(
            s ->
                studentService.bulkImport(
                    schoolId, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))));

    assertThat(result).isNotNull();
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.rejectedCount()).isEqualTo(4);
    assertThat(result.rejectedRows()).hasSize(4);

    TenantContext.set(schoolId);
    var students = tx.execute(s -> studentRepository.findAll());
    assertThat(students).hasSize(1);
    assertThat(students.get(0).getExternalId()).isEqualTo("EXT-001");
  }

  @Test
  void emptyFile_noHeaderRows_importsZero() {
    String csv = "externalId,fullName,dateOfBirth,className\n";

    BulkImportResult result =
        tx.execute(
            s ->
                studentService.bulkImport(
                    schoolId, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))));

    assertThat(result.importedCount()).isZero();
    assertThat(result.rejectedCount()).isZero();
  }
}
