package com.schoolbridge.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.schoolbridge.api.SqlIntegrationTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Platform-admin user management with real JWT authorization and SQL persistence checks. */
class UserControllerIntegrationTest extends SqlIntegrationTest {

  private static final String SCHOOL_ONE = "10000000-0000-0000-0000-000000000001";
  @Autowired JdbcTemplate jdbc;

  @Test
  void platformAdminCreatesListsAndReadsTenantUser() {
    String admin = login("admin@platform.test", "password");
    String id =
        authenticated(admin)
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "role",
                    "TEACHER",
                    "name",
                    "New Teacher",
                    "email",
                    "new.teacher@fixture.test",
                    "password",
                    "password1"))
            .post("/api/v1/schools/{schoolId}/users", SCHOOL_ONE)
            .then()
            .statusCode(201)
            .body("data.email", equalTo("new.teacher@fixture.test"))
            .extract()
            .path("data.id");

    assertThat(
            jdbc.queryForObject(
                "select count(*) from users where email = ?",
                Long.class,
                "new.teacher@fixture.test"))
        .isEqualTo(1L);
    authenticated(admin)
        .get("/api/v1/schools/{schoolId}/users/{id}", SCHOOL_ONE, id)
        .then()
        .statusCode(200)
        .body("data.id", equalTo(id));
    authenticated(admin).get("/api/v1/schools/{schoolId}/users", SCHOOL_ONE).then().statusCode(200);
  }

  @Test
  void userManagementRejectsStaffAndDuplicateOrInvalidRequests() {
    String teacher = login("teacher@fixture.test", "password");
    authenticated(teacher)
        .get("/api/v1/schools/{schoolId}/users", SCHOOL_ONE)
        .then()
        .statusCode(403);
    String admin = login("admin@platform.test", "password");
    authenticated(admin)
        .contentType(ContentType.JSON)
        .body(Map.of("role", "PARENT", "name", "No Phone"))
        .post("/api/v1/schools/{schoolId}/users", SCHOOL_ONE)
        .then()
        .statusCode(422);
    authenticated(admin)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "role",
                "TEACHER",
                "name",
                "Duplicate",
                "email",
                "teacher@fixture.test",
                "password",
                "password1"))
        .post("/api/v1/schools/{schoolId}/users", SCHOOL_ONE)
        .then()
        .statusCode(409);
  }
}
