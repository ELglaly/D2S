package com.schoolbridge.api.classes;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.classes.entity.Enrollment;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.jwt.JwtService;
import com.schoolbridge.api.identity.otp.OtpService;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import io.restassured.RestAssured;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ParentChildrenControllerTest extends AbstractIntegrationTest {

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
    tx.executeWithoutResult(s -> enrollmentRepository.deleteAll());
    tx.executeWithoutResult(s -> linkRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "SchoolA",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());

    schoolBId =
        tx.execute(
            s ->
                schoolRepository
                    .save(
                        new School(
                            "SchoolB",
                            "EG",
                            "Africa/Cairo",
                            "ar-EG",
                            SubscriptionTier.STANDARD,
                            SchoolSettings.defaults()))
                    .getId());

    classId =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolId, "3A", "Grade 3", "2025-2026", null))
                    .getId());

    studentId =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolId, "Ahmed Mohamed", LocalDate.of(2015, 3, 15), null))
                    .getId());

    tx.executeWithoutResult(
        s -> enrollmentRepository.save(new Enrollment(schoolId, studentId, classId)));

    parentId = persistParent(schoolId, "+201090000201");
    unlinkedParentId = persistParent(schoolId, "+201090000202");

    tx.executeWithoutResult(
        s ->
            linkRepository.save(
                new ParentStudentLink(
                    schoolId, parentId, studentId, RelationshipType.MOTHER, true)));

    UUID adminId =
        tx.execute(
            s ->
                userRepository
                    .save(
                        User.staff(
                            schoolId,
                            UserRole.SCHOOL_ADMIN,
                            "Admin",
                            "admin@parentchildren.test",
                            passwordEncoder.encode("pass")))
                    .getId());

    adminToken =
        jwtService.issueAccess(
            adminId.toString(),
            Map.of("kind", "USER", "schoolId", schoolId.toString(), "role", "SCHOOL_ADMIN"));

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
        .body("data[0].fullName", equalTo("Ahmed Mohamed"))
        .body("data[0].dateOfBirth", equalTo("2015-03-15"))
        .body("data[0].status", equalTo("ACTIVE"))
        .body("data[0].relationship", equalTo("MOTHER"))
        .body("data[0].primaryContact", equalTo(true))
        .body("data[0].classes", hasSize(1))
        .body("data[0].classes[0].classId", equalTo(classId.toString()))
        .body("data[0].classes[0].name", equalTo("3A"))
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
    UUID otherStudentId =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolId, "Unlinked Child", LocalDate.of(2016, 6, 1), null))
                    .getId());
    tx.executeWithoutResult(
        s -> enrollmentRepository.save(new Enrollment(schoolId, otherStudentId, classId)));

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
    UUID otherStudentId =
        tx.execute(
            s ->
                studentRepository
                    .save(new Student(schoolBId, "School B Child", LocalDate.of(2015, 1, 1), null))
                    .getId());
    UUID otherClassId =
        tx.execute(
            s ->
                classRepository
                    .save(new SchoolClass(schoolBId, "1A", "Grade 1", "2025-2026", null))
                    .getId());
    tx.executeWithoutResult(
        s -> enrollmentRepository.save(new Enrollment(schoolBId, otherStudentId, otherClassId)));
    // link parentId (school A) to the school B student — cross-tenant stale link
    tx.executeWithoutResult(
        s ->
            linkRepository.save(
                new ParentStudentLink(
                    schoolBId, parentId, otherStudentId, RelationshipType.FATHER, false)));

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
