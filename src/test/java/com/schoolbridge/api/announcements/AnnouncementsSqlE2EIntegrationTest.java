package com.schoolbridge.api.announcements;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.schoolbridge.api.SqlIntegrationTest;
import com.schoolbridge.api.identity.otp.OtpService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/** SQL-fixture-backed staff-to-parent announcement journey. */
@Sql(
    scripts = {
      "classpath:sql/fixtures/common/academic-structure.sql",
      "classpath:sql/fixtures/classes/parent-children.sql",
      "classpath:sql/fixtures/common/announcements.sql"
    })
class AnnouncementsSqlE2EIntegrationTest extends SqlIntegrationTest {

  private static final UUID ANNOUNCEMENT_ID =
      UUID.fromString("50000000-0000-0000-0000-000000000001");
  private static final UUID PARENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000013");

  @Autowired OtpService otpService;
  @Autowired JdbcTemplate jdbc;

  @Test
  void parentCanReadAndIdempotentlyAcknowledgeFixtureAnnouncement() {
    OtpService.IssuedOtp ticket =
        otpService.issue(PARENT_ID, UUID.fromString("10000000-0000-0000-0000-000000000001"));
    String token = otpService.verify(ticket.ticketId(), ticket.code()).token();

    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get("/api/v1/announcements/" + ANNOUNCEMENT_ID)
        .then()
        .statusCode(200)
        .body("data.id", equalTo(ANNOUNCEMENT_ID.toString()));

    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .post("/api/v1/announcements/" + ANNOUNCEMENT_ID + "/acknowledge")
        .then()
        .statusCode(204);
    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .post("/api/v1/announcements/" + ANNOUNCEMENT_ID + "/acknowledge")
        .then()
        .statusCode(204);

    Integer acknowledged =
        jdbc.queryForObject(
            "select count(*) from announcement_recipients "
                + "where announcement_id = ? and acknowledged_at is not null",
            Integer.class,
            ANNOUNCEMENT_ID);
    assertThat(acknowledged).isEqualTo(1);
  }

  @Test
  void scheduledFixtureHasNoCreatedOutboxUntilRelease() {
    Integer createdEvents =
        jdbc.queryForObject(
            "select count(*) from outbox_events "
                + "where aggregate_id = ? and event_type = 'announcement.created'",
            Integer.class,
            UUID.fromString("50000000-0000-0000-0000-000000000002"));
    assertThat(createdEvents).isZero();
  }
}
