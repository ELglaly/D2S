package com.schoolbridge.api.homework;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.SqlIntegrationTest;
import com.schoolbridge.api.grades.GradeRecordRepository;
import com.schoolbridge.api.identity.otp.OtpDispatcher;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.jdbc.Sql;

/** SQL-backed HTTP and role-specific workflow coverage for homework and grade endpoints. */
@Import(HomeworkGradesIntegrationTest.CaptureConfig.class)
@Sql(
    scripts = {
      "classpath:sql/cleanup/all-data.sql",
      "classpath:sql/fixtures/common/schools.sql",
      "classpath:sql/fixtures/common/principals.sql",
      "classpath:sql/fixtures/common/role-permissions.sql",
      "classpath:sql/fixtures/common/academic-structure.sql",
      "classpath:sql/fixtures/subjects/subject-assignments.sql",
      "classpath:sql/fixtures/homework/parent-link.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
    scripts = "classpath:sql/cleanup/all-data.sql",
    executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class HomeworkGradesIntegrationTest extends SqlIntegrationTest {
  private static final UUID CLASS_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
  private static final UUID STUDENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000011");
  @Autowired HomeworkItemRepository homework;
  @Autowired HomeworkRecipientRepository recipients;
  @Autowired GradeRecordRepository grades;
  @Autowired CapturingOtpDispatcher dispatcher;
  private String teacher;
  private String admin;

  @BeforeEach
  void credentials() {
    teacher = login("teacher@fixture.test", "password");
    admin = login("school-admin@fixture.test", "password");
    dispatcher.code.set(null);
  }

  @Test
  void publishedHomework_parentAcknowledgementAndGradeJourney_persistsState() {
    UUID homeworkId =
        responseId(
            authenticated(teacher)
                .contentType(ContentType.JSON)
                .body(homeworkBody(true, true))
                .post("/api/v1/homework")
                .then()
                .statusCode(201)
                .body("data.status", equalTo("PUBLISHED"))
                .body("data.recipientCount", equalTo(1))
                .extract()
                .response());
    assertThat(homework.findById(homeworkId)).isPresent();
    assertThat(recipients.findAllByHomeworkId(homeworkId)).hasSize(1);

    String parent = parentToken();
    authenticated(parent)
        .get("/api/v1/homework?childId={id}", STUDENT_ID)
        .then()
        .statusCode(200)
        .body("data", hasSize(1))
        .body("data[0].homeworkId", equalTo(homeworkId.toString()));
    authenticated(parent)
        .post("/api/v1/homework/{id}/acknowledge", homeworkId)
        .then()
        .statusCode(204);
    assertThat(recipients.findAllByHomeworkId(homeworkId).getFirst().getAcknowledgedAt())
        .isNotNull();

    UUID gradeId =
        responseId(
            authenticated(teacher)
                .contentType(ContentType.JSON)
                .body(gradeBody("Mathematics", "Term 1", "92.50"))
                .post("/api/v1/grades")
                .then()
                .statusCode(201)
                .extract()
                .response());
    authenticated(parent)
        .get("/api/v1/grades?studentId={id}", STUDENT_ID)
        .then()
        .statusCode(200)
        .body("data.id", hasItem(gradeId.toString()));
    authenticated(teacher)
        .contentType(ContentType.JSON)
        .body(Map.of("score", 95, "gradeLabel", "A", "notes", "Improved"))
        .patch("/api/v1/grades/{id}", gradeId)
        .then()
        .statusCode(200)
        .body("data.gradeLabel", equalTo("A"));
    assertThat(grades.findById(gradeId)).isPresent();
  }

  @Test
  void homeworkAndGrades_rejectInvalidTransitionsDuplicatesAndUnauthorizedWrites() {
    UUID draft =
        responseId(
            authenticated(teacher)
                .contentType(ContentType.JSON)
                .body(homeworkBody(true, false))
                .post("/api/v1/homework")
                .then()
                .statusCode(201)
                .extract()
                .response());
    authenticated(teacher).post("/api/v1/homework/{id}/publish", draft).then().statusCode(200);
    authenticated(teacher).post("/api/v1/homework/{id}/publish", draft).then().statusCode(409);
    authenticated(teacher).delete("/api/v1/homework/{id}", draft).then().statusCode(204);
    authenticated(teacher)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "subject", "Changed", "description", "No", "dueDate", LocalDate.now().plusDays(4)))
        .patch("/api/v1/homework/{id}", draft)
        .then()
        .statusCode(409);
    authenticated(teacher)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "classId",
                CLASS_ID.toString(),
                "subject",
                "Bad",
                "description",
                "Bad",
                "dueDate",
                LocalDate.now()))
        .post("/api/v1/homework")
        .then()
        .statusCode(422);

    authenticated(teacher)
        .contentType(ContentType.JSON)
        .body(gradeBody("Science", "Term 1", "80"))
        .post("/api/v1/grades")
        .then()
        .statusCode(201);
    authenticated(teacher)
        .contentType(ContentType.JSON)
        .body(gradeBody("Science", "Term 1", "80"))
        .post("/api/v1/grades")
        .then()
        .statusCode(409);
    authenticated(teacher).delete("/api/v1/grades/{id}", UUID.randomUUID()).then().statusCode(404);
    authenticated(admin)
        .get("/api/v1/grades?classId={id}", CLASS_ID)
        .then()
        .statusCode(200)
        .body("data", hasSize(1));
  }

  private Map<String, Object> homeworkBody(boolean ack, boolean publish) {
    return Map.of(
        "classId",
        CLASS_ID.toString(),
        "subject",
        "Mathematics",
        "description",
        "Complete exercises 1-5",
        "dueDate",
        LocalDate.now().plusDays(5),
        "requiresAck",
        ack,
        "publish",
        publish);
  }

  private Map<String, Object> gradeBody(String subject, String period, String score) {
    return Map.of(
        "studentId",
        STUDENT_ID.toString(),
        "classId",
        CLASS_ID.toString(),
        "subject",
        subject,
        "period",
        period,
        "score",
        score,
        "gradeLabel",
        "B",
        "notes",
        "Fixture grade");
  }

  private String parentToken() {
    String ticket =
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(Map.of("phone", "+201090000201"))
            .post("/api/v1/parents/auth/request-otp")
            .then()
            .statusCode(200)
            .body("data.ticketId", notNullValue())
            .extract()
            .path("data.ticketId");
    return RestAssured.given()
        .contentType(ContentType.JSON)
        .body(Map.of("ticketId", ticket, "code", dispatcher.code.get()))
        .post("/api/v1/parents/auth/verify-otp")
        .then()
        .statusCode(200)
        .extract()
        .path("data.token");
  }

  @TestConfiguration
  static class CaptureConfig {
    @Bean
    @Primary
    CapturingOtpDispatcher capturingOtpDispatcher() {
      return new CapturingOtpDispatcher();
    }
  }

  static class CapturingOtpDispatcher implements OtpDispatcher {
    final AtomicReference<String> code = new AtomicReference<>();

    @Override
    public void dispatch(String phone, String value) {
      code.set(value);
    }
  }
}
