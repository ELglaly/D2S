package com.schoolbridge.api.subjects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.schoolbridge.api.SqlIntegrationTest;
import io.restassured.http.ContentType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

/** Real HTTP coverage for the subject catalogue and its class/teacher relationships. */
@Sql(
    scripts = {
      "classpath:sql/cleanup/all-data.sql",
      "classpath:sql/fixtures/common/schools.sql",
      "classpath:sql/fixtures/common/principals.sql",
      "classpath:sql/fixtures/common/role-permissions.sql",
      "classpath:sql/fixtures/common/academic-structure.sql",
      "classpath:sql/fixtures/subjects/subject-assignments.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
    scripts = "classpath:sql/cleanup/all-data.sql",
    executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class SubjectsIntegrationTest extends SqlIntegrationTest {

  private static final UUID CLASS_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
  private static final UUID STUDENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000011");
  private static final UUID SUBJECT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_SUBJECT_ID =
      UUID.fromString("40000000-0000-0000-0000-000000000002");
  private static final UUID TEACHER_ID = UUID.fromString("20000000-0000-0000-0000-000000000010");
  private static final UUID CLASS_SUBJECT_ID =
      UUID.fromString("40000000-0000-0000-0000-000000000011");
  private static final UUID ASSIGNMENT_ID = UUID.fromString("40000000-0000-0000-0000-000000000021");

  @Autowired SubjectRepository subjects;
  @Autowired ClassSubjectRepository classSubjects;
  @Autowired TeacherSubjectAssignmentRepository assignments;

  private String adminToken;
  private String teacherToken;
  private String otherTenantAdminToken;

  @BeforeEach
  void authenticateFixtureUsers() {
    adminToken = login("school-admin@fixture.test", "password");
    teacherToken = login("teacher@fixture.test", "password");
    otherTenantAdminToken = login("school-two-admin@fixture.test", "password");
  }

  @Test
  void subjectCrudValidationConflictAndTenantIsolation_areEnforced() {
    UUID created =
        responseId(
            authenticated(adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("name", "  Physics  ", "code", " PHY-1 ", "description", "Lab"))
                .post("/api/v1/subjects")
                .then()
                .statusCode(201)
                .body("data.id", notNullValue())
                .body("data.name", equalTo("Physics"))
                .body("data.code", equalTo("PHY-1"))
                .extract()
                .response());
    assertThat(subjects.findById(created)).isPresent();

    authenticated(adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("name", "Physics"))
        .post("/api/v1/subjects")
        .then()
        .statusCode(409);
    authenticated(adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("name", "", "status", "ACTIVE"))
        .post("/api/v1/subjects")
        .then()
        .statusCode(422);
    authenticated(adminToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name",
                "Physics II",
                "code",
                "PHY-2",
                "description",
                "Advanced",
                "status",
                "INACTIVE"))
        .patch("/api/v1/subjects/{id}", created)
        .then()
        .statusCode(200)
        .body("data.name", equalTo("Physics II"))
        .body("data.status", equalTo("INACTIVE"));

    authenticated(adminToken).get("/api/v1/subjects/{id}", OTHER_SUBJECT_ID).then().statusCode(404);
    authenticated(otherTenantAdminToken)
        .get("/api/v1/subjects/{id}", created)
        .then()
        .statusCode(404);
    authenticated(adminToken).delete("/api/v1/subjects/{id}", created).then().statusCode(204);
    assertThat(subjects.findById(created)).isEmpty();
    authenticated(adminToken).get("/api/v1/subjects/{id}", created).then().statusCode(404);
  }

  @Test
  void subjectReadsAndWrites_obeyPermissions() {
    authenticated(adminToken)
        .get("/api/v1/subjects?size=5")
        .then()
        .statusCode(200)
        .body("data", hasSize(1))
        .body("data[0].id", equalTo(SUBJECT_ID.toString()));
    authenticated(teacherToken).get("/api/v1/subjects").then().statusCode(200);
    authenticated(teacherToken)
        .contentType(ContentType.JSON)
        .body(Map.of("name", "Forbidden"))
        .post("/api/v1/subjects")
        .then()
        .statusCode(403);
  }

  @Test
  void classSubjectLifecycle_rejectsDuplicatesAndReferencedRemoval() {
    authenticated(adminToken)
        .get("/api/v1/classes/{classId}/subjects", CLASS_ID)
        .then()
        .statusCode(200)
        .body("data", hasSize(1))
        .body("data[0].subjectId", equalTo(SUBJECT_ID.toString()));
    authenticated(adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("subjectId", SUBJECT_ID.toString()))
        .post("/api/v1/classes/{classId}/subjects", CLASS_ID)
        .then()
        .statusCode(409);
    authenticated(adminToken)
        .delete("/api/v1/class-subjects/{id}", CLASS_SUBJECT_ID)
        .then()
        .statusCode(409);
    authenticated(adminToken)
        .delete("/api/v1/teacher-subject-assignments/{id}", ASSIGNMENT_ID)
        .then()
        .statusCode(204);
    assertThat(assignments.findById(ASSIGNMENT_ID)).isEmpty();
    authenticated(adminToken)
        .delete("/api/v1/class-subjects/{id}", CLASS_SUBJECT_ID)
        .then()
        .statusCode(204);
    assertThat(classSubjects.findById(CLASS_SUBJECT_ID)).isEmpty();
    authenticated(adminToken)
        .get("/api/v1/classes/{classId}/subjects", CLASS_ID)
        .then()
        .body("data", hasSize(0));
  }

  @Test
  void teacherAssignments_validateRelationshipsAndStudentResolution() {
    authenticated(adminToken)
        .get("/api/v1/students/{studentId}/subjects", STUDENT_ID)
        .then()
        .statusCode(200)
        .body("data", hasSize(1))
        .body("data[0].subjectId", equalTo(SUBJECT_ID.toString()))
        .body("data[0].teacherUserId", equalTo(TEACHER_ID.toString()));
    authenticated(adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("teacherUserId", TEACHER_ID.toString()))
        .post("/api/v1/classes/{classId}/subjects/{subjectId}/teachers", CLASS_ID, SUBJECT_ID)
        .then()
        .statusCode(409);
    authenticated(adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("teacherUserId", TEACHER_ID.toString()))
        .post(
            "/api/v1/classes/{classId}/subjects/{subjectId}/teachers", CLASS_ID, UUID.randomUUID())
        .then()
        .statusCode(404);
    authenticated(adminToken)
        .contentType(ContentType.JSON)
        .body(Map.of("teacherUserId", "20000000-0000-0000-0000-000000000011"))
        .post("/api/v1/classes/{classId}/subjects/{subjectId}/teachers", CLASS_ID, SUBJECT_ID)
        .then()
        .statusCode(404);
    authenticated(adminToken)
        .delete("/api/v1/teacher-subject-assignments/{id}", ASSIGNMENT_ID)
        .then()
        .statusCode(204);
    authenticated(adminToken)
        .get("/api/v1/classes/{classId}/subjects/{subjectId}/teachers", CLASS_ID, SUBJECT_ID)
        .then()
        .statusCode(200)
        .body("data", hasSize(0));
    authenticated(adminToken)
        .get("/api/v1/students/{studentId}/subjects", STUDENT_ID)
        .then()
        .statusCode(200)
        .body("data", hasSize(1))
        .body("data[0].teacherUserId", nullValue());
  }

  @Test
  void adminWorkflow_createAttachAssignResolveAndRemove_persistsEveryTransition() {
    UUID subject =
        responseId(
            authenticated(adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("name", "Workflow Art", "code", "ART-3"))
                .post("/api/v1/subjects")
                .then()
                .statusCode(201)
                .extract()
                .response());
    UUID classSubject =
        responseId(
            authenticated(adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("subjectId", subject.toString()))
                .post("/api/v1/classes/{classId}/subjects", CLASS_ID)
                .then()
                .statusCode(201)
                .extract()
                .response());
    UUID assignment =
        responseId(
            authenticated(adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("teacherUserId", TEACHER_ID.toString()))
                .post("/api/v1/classes/{classId}/subjects/{subjectId}/teachers", CLASS_ID, subject)
                .then()
                .statusCode(201)
                .extract()
                .response());
    assertThat(subjects.findById(subject)).isPresent();
    assertThat(classSubjects.findById(classSubject)).isPresent();
    assertThat(assignments.findById(assignment)).isPresent();
    authenticated(adminToken)
        .get("/api/v1/students/{studentId}/subjects", STUDENT_ID)
        .then()
        .statusCode(200)
        .body("data.subjectId", hasItem(subject.toString()));
    authenticated(adminToken)
        .delete("/api/v1/teacher-subject-assignments/{id}", assignment)
        .then()
        .statusCode(204);
    assertThat(assignments.findById(assignment)).isEmpty();
  }
}
