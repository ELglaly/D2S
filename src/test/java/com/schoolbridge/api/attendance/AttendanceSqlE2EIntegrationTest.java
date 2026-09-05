package com.schoolbridge.api.attendance;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.schoolbridge.api.SqlIntegrationTest;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.jwt.JwtService;
import com.schoolbridge.api.identity.otp.OtpService;
import io.restassured.http.ContentType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/** SQL-fixture-backed attendance journey covering validation, idempotency, and parent response. */
@Sql(
    scripts = {
      "classpath:sql/fixtures/common/academic-structure.sql",
      "classpath:sql/fixtures/classes/parent-children.sql",
      "classpath:sql/fixtures/common/attendance.sql"
    })
class AttendanceSqlE2EIntegrationTest extends SqlIntegrationTest {

  private static final UUID SCHOOL_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID STUDENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000011");
  private static final UUID CLASS_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
  private static final UUID PARENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000013");
  private static final UUID ABSENT_RECORD_ID =
      UUID.fromString("60000000-0000-0000-0000-000000000001");

  @Autowired JwtService jwtService;
  @Autowired OtpService otpService;
  @Autowired JdbcTemplate jdbc;

  private String token(UUID userId, UserRole role) {
    if (role == UserRole.PARENT) {
      OtpService.IssuedOtp ticket = otpService.issue(userId, SCHOOL_ID);
      return otpService.verify(ticket.ticketId(), ticket.code()).token();
    }
    return jwtService.issueAccess(
        userId.toString(),
        Map.of(
            "kind", "USER",
            "schoolId", SCHOOL_ID.toString(),
            "role", role.name()));
  }

  @Test
  void teacherMarkIsIdempotentAndRejectsUnenrolledStudent() {
    String admin =
        token(UUID.fromString("20000000-0000-0000-0000-000000000011"), UserRole.SCHOOL_ADMIN);
    Map<String, Object> body =
        Map.of(
            "studentId", STUDENT_ID, "classId", CLASS_ID, "date", "2025-02-12", "status", "ABSENT");
    given()
        .header("Authorization", "Bearer " + admin)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/attendance/mark")
        .then()
        .statusCode(200)
        .body("data.status", equalTo("ABSENT"));
    given()
        .header("Authorization", "Bearer " + admin)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/v1/attendance/mark")
        .then()
        .statusCode(200);
    Integer events =
        jdbc.queryForObject(
            "select count(*) from outbox_events "
                + "where event_type = 'attendance.absent_alert' and payload->>'studentId' = ?",
            Integer.class,
            STUDENT_ID.toString());
    assertThat(events).isEqualTo(1);

    Map<String, Object> invalid =
        Map.of(
            "studentId",
            UUID.fromString("30000000-0000-0000-0000-000000000012"),
            "classId",
            CLASS_ID,
            "date",
            "2025-02-13",
            "status",
            "PRESENT");
    given()
        .header("Authorization", "Bearer " + admin)
        .contentType(ContentType.JSON)
        .body(invalid)
        .when()
        .post("/api/v1/attendance/mark")
        .then()
        .statusCode(422);
  }

  @Test
  void linkedParentCanRespondAndResponseIsPersisted() {
    given()
        .header("Authorization", "Bearer " + token(PARENT_ID, UserRole.PARENT))
        .contentType(ContentType.JSON)
        .body(Map.of("response", "Medical appointment"))
        .when()
        .post("/api/v1/attendance/" + ABSENT_RECORD_ID + "/parent-response")
        .then()
        .statusCode(200)
        .body("data.id", equalTo(ABSENT_RECORD_ID.toString()));
    Integer responses =
        jdbc.queryForObject(
            "select count(*) from attendance_records "
                + "where id = ? and parent_response is not null",
            Integer.class,
            ABSENT_RECORD_ID);
    assertThat(responses).isEqualTo(1);
  }
}
