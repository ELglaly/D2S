package com.schoolbridge.api.grades;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.identity.jwt.JwtService;
import io.restassured.RestAssured;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Verifies the Phase-2 migration of {@code GradesController} from {@code @PreAuthorize("hasRole")}
 * to {@code @RequirePermission}. The grants seeded by {@code 015-authz.sql} are the source of
 * truth: SCHOOL_ADMIN holds {@code GRADE_DELETE}; TEACHER does not. Enforcement runs in {@link
 * com.schoolbridge.api.common.security.authz.PermissionAspect} before any handler body, so the
 * teacher is denied with 403 and the admin falls through to a 404 for a non-existent record.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GradesAuthorizationIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort int port;
  @Autowired JwtService jwtService;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
  }

  @Test
  void delete_withoutToken_returns401() {
    given().when().delete("/api/v1/grades/" + UUID.randomUUID()).then().statusCode(401);
  }

  @Test
  void delete_asTeacher_returns403_lacksGradeDelete() {
    given()
        .header("Authorization", "Bearer " + staffToken("TEACHER"))
        .when()
        .delete("/api/v1/grades/" + UUID.randomUUID())
        .then()
        .statusCode(403)
        .body("type", equalTo("https://schoolbridge.app/errors/authorization"));
  }

  @Test
  void delete_asSchoolAdmin_passesAspectThen404() {
    given()
        .header("Authorization", "Bearer " + staffToken("SCHOOL_ADMIN"))
        .when()
        .delete("/api/v1/grades/" + UUID.randomUUID())
        .then()
        .statusCode(404);
  }

  @Test
  void get_asTeacher_passesAspectThen404_hasGradeRead() {
    given()
        .header("Authorization", "Bearer " + staffToken("TEACHER"))
        .when()
        .get("/api/v1/grades/" + UUID.randomUUID())
        .then()
        .statusCode(404);
  }

  private String staffToken(String role) {
    return jwtService.issueAccess(
        UUID.randomUUID().toString(),
        Map.of("kind", "USER", "role", role, "schoolId", UUID.randomUUID().toString()));
  }
}
