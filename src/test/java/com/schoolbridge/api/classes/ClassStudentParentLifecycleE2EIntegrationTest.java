package com.schoolbridge.api.classes;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import com.schoolbridge.api.SqlIntegrationTest;
import com.schoolbridge.api.identity.otp.OtpDispatcher;
import io.restassured.http.ContentType;
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

/** School-admin and parent journey using API-issued staff and parent credentials. */
@Import(ClassStudentParentLifecycleE2EIntegrationTest.CaptureOtpConfig.class)
class ClassStudentParentLifecycleE2EIntegrationTest extends SqlIntegrationTest {

  private static final String PARENT_PHONE = "+201090000201";
  private static final String PARENT_ID = "20000000-0000-0000-0000-000000000013";

  @Autowired CapturingOtpDispatcher otpDispatcher;

  @BeforeEach
  void clearCapturedCode() {
    otpDispatcher.lastCode.set(null);
  }

  @Test
  void schoolAdminCreatesAndRemovesClassStudentEnrollmentAndParentLink() {
    String adminToken = login("school-admin@fixture.test", "password");
    UUID classId =
        UUID.fromString(
            authenticated(adminToken)
                .contentType(ContentType.JSON)
                .body(
                    Map.of("name", "E2E 4A", "gradeLevel", "Grade 4", "academicYear", "2025-2026"))
                .post("/api/v1/classes")
                .then()
                .statusCode(201)
                .extract()
                .path("data.id"));
    UUID studentId =
        UUID.fromString(
            authenticated(adminToken)
                .contentType(ContentType.JSON)
                .body(
                    Map.of(
                        "fullName",
                        "E2E Child",
                        "dateOfBirth",
                        "2015-03-15",
                        "externalId",
                        "E2E-CHILD-1"))
                .post("/api/v1/students")
                .then()
                .statusCode(201)
                .extract()
                .path("data.id"));

    UUID enrollmentId =
        UUID.fromString(
            authenticated(adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("studentId", studentId.toString()))
                .post("/api/v1/classes/" + classId + "/enrollments")
                .then()
                .statusCode(201)
                .extract()
                .path("data.id"));
    UUID linkId =
        UUID.fromString(
            authenticated(adminToken)
                .contentType(ContentType.JSON)
                .body(
                    Map.of(
                        "parentUserId",
                        PARENT_ID,
                        "studentId",
                        studentId.toString(),
                        "relationship",
                        "MOTHER",
                        "primaryContact",
                        true))
                .post("/api/v1/parent-links")
                .then()
                .statusCode(201)
                .extract()
                .path("data.id"));

    String ticketId =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("phone", PARENT_PHONE))
            .post("/api/v1/parents/auth/request-otp")
            .then()
            .statusCode(200)
            .extract()
            .path("data.ticketId");
    String parentToken =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("ticketId", ticketId, "code", otpDispatcher.lastCode.get()))
            .post("/api/v1/parents/auth/verify-otp")
            .then()
            .statusCode(200)
            .extract()
            .path("data.token");
    authenticated(parentToken)
        .get("/api/v1/parents/me/children")
        .then()
        .statusCode(200)
        .body("data.studentId", hasItem(studentId.toString()));

    authenticated(adminToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "fullName", "E2E Child Updated", "dateOfBirth", "2015-03-15", "status", "ACTIVE"))
        .patch("/api/v1/students/" + studentId)
        .then()
        .statusCode(200)
        .body("data.fullName", equalTo("E2E Child Updated"));
    authenticated(adminToken).delete("/api/v1/parent-links/" + linkId).then().statusCode(204);
    authenticated(adminToken).delete("/api/v1/enrollments/" + enrollmentId).then().statusCode(204);
    authenticated(adminToken).delete("/api/v1/students/" + studentId).then().statusCode(204);
    authenticated(adminToken).delete("/api/v1/classes/" + classId).then().statusCode(204);
  }

  @TestConfiguration
  static class CaptureOtpConfig {
    @Bean
    @Primary
    CapturingOtpDispatcher capturingOtpDispatcher() {
      return new CapturingOtpDispatcher();
    }
  }

  static class CapturingOtpDispatcher implements OtpDispatcher {
    final AtomicReference<String> lastCode = new AtomicReference<>();

    @Override
    public void dispatch(String phone, String code) {
      lastCode.set(code);
    }
  }
}
