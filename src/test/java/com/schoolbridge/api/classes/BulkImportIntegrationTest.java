package com.schoolbridge.api.classes;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.dto.BulkImportResult;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.classes.service.StudentService;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.tenant.SchoolRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration test for {@link StudentService#bulkImport}. Covers happy path (all rows valid with
 * class enrollment) and bad-row rejection (various error scenarios).
 */
@SpringBootTest
@Sql(
    scripts = {
      "classpath:sql/cleanup/all-data.sql",
      "classpath:sql/fixtures/common/schools.sql",
      "classpath:sql/fixtures/identity/auth-principals.sql",
      "classpath:sql/fixtures/classes/bulk-import.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
    scripts = "classpath:sql/cleanup/all-data.sql",
    executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
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
    schoolId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    classId = UUID.fromString("30000000-0000-0000-0000-000000000041");

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
            + "EXT-001,Ahmed Mohamed,2015-03-15,Fixture Import 3A\n"
            + "EXT-002,Fatima Hassan,2014-07-22,Fixture Import 3A\n";

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
            + "EXT-001,Ahmed Mohamed,2015-03-15,Fixture Import 3A\n" // valid
            + "EXT-002,,2014-07-22,Fixture Import 3A\n" // missing fullName
            + "EXT-003,Hassan Ali,not-a-date,Fixture Import 3A\n" // bad date
            + "EXT-004,Sara Nour,2016-01-10,NonExistentClass\n" // class not found
            + "EXT-001,Duplicate ID,2017-05-20,Fixture Import 3A\n"; // duplicate externalId

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

  @Test
  void quotedFieldsAndSameFileDuplicates_areHandledWithoutCorruption() {
    String csv =
        "externalId,fullName,dateOfBirth,className\n"
            + "EXT-QUOTED,\"Ahmed, Mohamed\",2015-03-15,Fixture Import 3A\n"
            + "EXT-QUOTED,Duplicate,2015-03-15,Fixture Import 3A\n";

    BulkImportResult result =
        tx.execute(
            s ->
                studentService.bulkImport(
                    schoolId, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))));

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.rejectedCount()).isEqualTo(1);
    assertThat(result.rejectedRows().getFirst().reason()).contains("duplicated");
    TenantContext.set(schoolId);
    assertThat(tx.execute(s -> studentRepository.findAll()).getFirst().getFullName())
        .isEqualTo("Ahmed, Mohamed");
  }

  @Test
  void invalidHeader_isRejectedBeforeAnyWrite() {
    String csv = "id,name,dateOfBirth,className\nEXT-001,Ahmed,2015-03-15,Fixture Import 3A\n";

    BulkImportResult result =
        tx.execute(
            s ->
                studentService.bulkImport(
                    schoolId, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))));

    assertThat(result.importedCount()).isZero();
    assertThat(result.rejectedCount()).isEqualTo(1);
    assertThat(result.rejectedRows().getFirst().rowNumber()).isEqualTo(1);
    TenantContext.set(schoolId);
    var students = tx.execute(s -> studentRepository.findAll());
    assertThat(students).isEmpty();
  }
}
