package com.schoolbridge.api.classes;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.schoolbridge.api.SqlIntegrationTest;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.jwt.JwtService;
import com.schoolbridge.api.identity.otp.OtpService;
import com.schoolbridge.api.tenant.SchoolRepository;
import io.restassured.RestAssured;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql(
    scripts = {
      "classpath:sql/cleanup/all-data.sql",
      "classpath:sql/fixtures/common/schools.sql",
      "classpath:sql/fixtures/identity/auth-principals.sql",
      "classpath:sql/fixtures/classes/academic-roster.sql",
      "classpath:sql/fixtures/classes/parent-children.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
    scripts = "classpath:sql/cleanup/all-data.sql",
    executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ParentChildrenControllerTest extends SqlIntegrationTest {

  private static final String ENDPOINT = "/api/v1/parents/me/children";

  @LocalServerPort int port;

  @Autowired EnrollmentRepository enrollmentRepository;
  @Autowired ParentStudentLinkRepository linkRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JwtService jwtService;
  @Autowired OtpService otpService;
  @Autowired TransactionTemplate tx;

  private UUID schoolId;
  private UUID schoolBId;
  private UUID studentId;
  private UUID classId;
  private UUID parentId;
  private UUID unlinkedParentId;
  private String parentToken;
  private String unlinkedParentToken;
  private String adminToken;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    schoolId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    schoolBId = UUID.fromString("10000000-0000-0000-0000-000000000002");
    classId = UUID.fromString("30000000-0000-0000-0000-000000000001");
    studentId = UUID.fromString("30000000-0000-0000-0000-000000000011");
    parentId = UUID.fromString("20000000-0000-0000-0000-000000000013");
    unlinkedParentId = UUID.fromString("20000000-0000-0000-0000-000000000014");
    adminToken = login("school-admin@fixture.test", "password");
    parentToken = issueParentToken(parentId);
    unlinkedParentToken = issueParentToken(unlinkedParentId);
  }

  @Test
  void myChildren_linkedParent_returnsChildrenWithClasses() {
    given()
        .header("Authorization", "Bearer " + parentToken)
        .when()
        .get(ENDPOINT)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data", hasSize(1))
        .body("data[0].studentId", equalTo(studentId.toString()))
        .body("data[0].fullName", equalTo("Fixture Linked Student"))
        .body("data[0].dateOfBirth", equalTo("2015-03-15"))
        .body("data[0].status", equalTo("ACTIVE"))
        .body("data[0].relationship", equalTo("MOTHER"))
        .body("data[0].primaryContact", equalTo(true))
        .body("data[0].classes", hasSize(1))
        .body("data[0].classes[0].classId", equalTo(classId.toString()))
        .body("data[0].classes[0].name", equalTo("Fixture 3A"))
        .body("data[0].classes[0].gradeLevel", equalTo("Grade 3"))
        .body("data[0].classes[0].academicYear", equalTo("2025-2026"));
  }

  @Test
  void myChildren_parentWithNoLinks_returnsEmptyList() {
    given()
        .header("Authorization", "Bearer " + unlinkedParentToken)
        .when()
        .get(ENDPOINT)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data", hasSize(0));
  }

  @Test
  void myChildren_doesNotReturnUnlinkedChild() {
    given()
        .header("Authorization", "Bearer " + parentToken)
        .when()
        .get(ENDPOINT)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data", hasSize(1))
        .body("data[0].studentId", equalTo(studentId.toString()));
  }

  @Test
  void myChildren_crossTenant_excludesOtherSchoolChild() {
    // link parentId (school A) to the school B student — cross-tenant stale link
    given()
        .header("Authorization", "Bearer " + parentToken)
        .when()
        .get(ENDPOINT)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data", hasSize(1))
        .body("data[0].studentId", equalTo(studentId.toString()));
  }

  @Test
  void myChildren_withAdminToken_returns403() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .when()
        .get(ENDPOINT)
        .then()
        .statusCode(403);
  }

  @Test
  void myChildren_withoutAuth_returns401() {
    given().when().get(ENDPOINT).then().statusCode(401);
  }

  // --- helpers ---

  private UUID persistParent(UUID school, String phone) {
    return tx.execute(
        s ->
            userRepository
                .save(User.parent(school, "Parent " + phone, phone, blindIndex.hash(phone)))
                .getId());
  }

  private String issueParentToken(UUID pid) {
    OtpService.IssuedOtp ticket = otpService.issue(pid, schoolId);
    OtpService.ParentSession session = otpService.verify(ticket.ticketId(), ticket.code());
    return session.token();
  }
}
