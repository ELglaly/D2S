package com.schoolbridge.api.common.security.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;

import com.schoolbridge.api.SqlIntegrationTest;
import com.schoolbridge.api.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runtime permission changes must take effect on the next protected request. */
class RolePermissionAdminIntegrationTest extends SqlIntegrationTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void platformAdminCanGrantRevokeAndReadCatalogWhileTeacherIsDenied() {
    String admin = login("admin@platform.test", "password");
    String teacher = login("teacher@fixture.test", "password");
    authenticated(teacher).get("/api/v1/admin/authz/permissions").then().statusCode(403);
    authenticated(admin)
        .get("/api/v1/admin/authz/permissions")
        .then()
        .statusCode(200)
        .body("data.name", hasItem("MANAGE_PERMISSIONS"));

    authenticated(admin)
        .post(
            "/api/v1/admin/authz/roles/{role}/permissions/{permission}",
            UserRole.TEACHER,
            Permission.USER_MANAGE)
        .then()
        .statusCode(204);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from role_permissions rp join permissions p on p.id = rp.permission_id where rp.role = 'TEACHER' and p.name = 'USER_MANAGE'",
                Long.class))
        .isEqualTo(1L);
    authenticated(teacher)
        .get("/api/v1/schools/10000000-0000-0000-0000-000000000001/users")
        .then()
        .statusCode(200);
    authenticated(admin)
        .post(
            "/api/v1/admin/authz/roles/{role}/permissions/{permission}",
            UserRole.TEACHER,
            Permission.USER_MANAGE)
        .then()
        .statusCode(204);
    authenticated(admin)
        .delete(
            "/api/v1/admin/authz/roles/{role}/permissions/{permission}",
            UserRole.TEACHER,
            Permission.USER_MANAGE)
        .then()
        .statusCode(204);
    authenticated(teacher)
        .get("/api/v1/schools/10000000-0000-0000-0000-000000000001/users")
        .then()
        .statusCode(403);
  }

  @Test
  void invalidRoleOrPermissionIsRejected() {
    String admin = login("admin@platform.test", "password");
    authenticated(admin)
        .post("/api/v1/admin/authz/roles/NOT_A_ROLE/permissions/USER_READ")
        .then()
        .statusCode(422);
    authenticated(admin)
        .post("/api/v1/admin/authz/roles/TEACHER/permissions/NOT_A_PERMISSION")
        .then()
        .statusCode(422);
  }
}
