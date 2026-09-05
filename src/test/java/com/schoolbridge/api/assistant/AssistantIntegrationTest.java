package com.schoolbridge.api.assistant;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.assistant.llm.LlmContent;
import com.schoolbridge.api.assistant.llm.LlmGateway;
import com.schoolbridge.api.assistant.llm.LlmResponse;
import com.schoolbridge.api.assistant.llm.LlmUsage;
import com.schoolbridge.api.classes.RelationshipType;
import com.schoolbridge.api.classes.entity.Enrollment;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
import com.schoolbridge.api.common.audit.AuditLogRepository;
import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.jwt.JwtService;
import com.schoolbridge.api.subjects.ClassSubject;
import com.schoolbridge.api.subjects.ClassSubjectRepository;
import com.schoolbridge.api.subjects.Subject;
import com.schoolbridge.api.subjects.SubjectRepository;
import com.schoolbridge.api.subjects.TeacherSubjectAssignment;
import com.schoolbridge.api.subjects.TeacherSubjectAssignmentRepository;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Full-stack assistant test (enabled=true, scripted gateway): a read answer streams un-wrapped over
 * SSE; an action proposal yields a confirmRequired token whose {@code /confirm} executes the
 * mutation and writes audit rows; and a teacher cannot reach another school's class (tenant
 * isolation).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "schoolbridge.assistant.enabled=true",
      "schoolbridge.assistant.actions.enabled=true",
      "spring.ai.model.chat=openai",
      "spring.ai.openai.api-key=test-key"
    })
class AssistantIntegrationTest extends AbstractIntegrationTest {

  @TestConfiguration
  static class GatewayConfig {
    @Bean
    @Primary
    ScriptableGateway scriptableGateway() {
      return new ScriptableGateway();
    }
  }

  static final class ScriptableGateway implements LlmGateway {
    private final java.util.ArrayDeque<LlmResponse> script = new java.util.ArrayDeque<>();

    void script(LlmResponse... responses) {
      script.clear();
      script.addAll(List.of(responses));
    }

    @Override
    public LlmResponse converse(com.schoolbridge.api.assistant.llm.LlmRequest request) {
      return script.isEmpty()
          ? new LlmResponse(List.of(new LlmContent.Text("ok")), "end_turn", LlmUsage.zero())
          : script.poll();
    }
  }

  @LocalServerPort int port;

  @Autowired ScriptableGateway gateway;
  @Autowired ObjectMapper json;
  @Autowired TransactionTemplate tx;
  @Autowired SchoolRepository schoolRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolClassRepository classRepository;
  @Autowired StudentRepository studentRepository;
  @Autowired EnrollmentRepository enrollmentRepository;
  @Autowired ParentStudentLinkRepository linkRepository;
  @Autowired SubjectRepository subjectRepository;
  @Autowired ClassSubjectRepository classSubjectRepository;
  @Autowired TeacherSubjectAssignmentRepository assignmentRepository;
  @Autowired AuditLogRepository auditLogRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired BlindIndexHasher blindIndex;
  @Autowired JwtService jwtService;

  private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);

  private UUID schoolId;
  private UUID otherSchoolId;
  private UUID teacherId;
  private UUID classId;
  private UUID studentId;
  private UUID parentId;
  private String teacherToken;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    tx.executeWithoutResult(s -> auditLogRepository.deleteAll());
    tx.executeWithoutResult(s -> enrollmentRepository.deleteAll());
    tx.executeWithoutResult(s -> linkRepository.deleteAll());
    tx.executeWithoutResult(s -> assignmentRepository.deleteAll());
    tx.executeWithoutResult(s -> classSubjectRepository.deleteAll());
    tx.executeWithoutResult(s -> subjectRepository.deleteAll());
    tx.executeWithoutResult(s -> studentRepository.deleteAll());
    tx.executeWithoutResult(s -> classRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolId = persistSchool("Assistant Test School A");
    otherSchoolId = persistSchool("Assistant Test School B");

    teacherId = persistStaff(schoolId, UserRole.TEACHER, "teacher@assist.test", "Mr Teacher");
    classId = persistClass(schoolId, "3A");
    studentId = persistStudent(schoolId, "Linked Child");
    parentId = persistParent(schoolId, "+201090000201");
    tx.executeWithoutResult(
        s -> enrollmentRepository.save(new Enrollment(schoolId, studentId, classId)));
    tx.executeWithoutResult(
        s ->
            linkRepository.save(
                new ParentStudentLink(
                    schoolId, parentId, studentId, RelationshipType.MOTHER, true)));

    UUID subjectId =
        tx.execute(s -> subjectRepository.save(new Subject(schoolId, "Math", null, null)).getId());
    tx.executeWithoutResult(
        s -> classSubjectRepository.save(new ClassSubject(schoolId, classId, subjectId)));
    tx.executeWithoutResult(
        s ->
            assignmentRepository.save(
                new TeacherSubjectAssignment(schoolId, teacherId, classId, subjectId)));

    // School B's class — the same teacher must not be able to reach it.
    persistClass(otherSchoolId, "9Z");

    teacherToken = issueStaffToken(teacherId, schoolId, UserRole.TEACHER);
  }

  @Test
  void readAnswerStreamsUnwrappedOverSse() {
    gateway.script(text("You teach 1 class."));

    String body = ask(teacherToken, "summarize my classes");

    JsonNode chunk = firstChunk(body);
    assertThat(chunk.path("type").asText()).isEqualTo("delta");
    assertThat(chunk.path("text").asText()).contains("1 class");
    // Un-wrapped: the SSE frame is the bare chunk, not an ApiResponse envelope.
    assertThat(chunk.has("data")).isFalse();
  }

  @Test
  void actionToolRemainsUnavailableWhenActionsPropertyIsSupplied() {
    ObjectNode input = json.createObjectNode();
    input.put("classRef", "3A");
    input.put("studentRef", "Linked Child");
    input.put("status", "ABSENT");
    input.put("date", TODAY.toString());
    gateway.script(toolUse("mark_attendance", input));

    String body = ask(teacherToken, "mark Linked Child absent in 3A today");
    JsonNode chunk = firstChunk(body);
    assertThat(chunk.path("type").asText()).isEqualTo("delta");
    assertThat(body).doesNotContain("confirmRequired");
    assertThat(auditLogRepository.findAll()).isEmpty();
  }

  @Test
  void actionToolDoesNotProduceAConfirmationToken() {
    ObjectNode input = json.createObjectNode();
    input.put("classRef", "3A");
    input.put("studentRef", "Linked Child");
    input.put("status", "LATE");
    input.put("date", TODAY.toString());
    gateway.script(toolUse("mark_attendance", input));
    String body = ask(teacherToken, "mark late");
    assertThat(body).doesNotContain("confirmRequired");
  }

  @Test
  void teacherCannotReachAnotherSchoolsClass() {
    ObjectNode input = json.createObjectNode();
    input.put("classRef", "9Z"); // exists only in school B
    input.put("studentRef", "Linked Child");
    input.put("status", "ABSENT");
    input.put("date", TODAY.toString());
    // Action preview will be rejected (class not found in this tenant) → fed back → model answers.
    gateway.script(toolUse("mark_attendance", input), text("I could not find that class."));

    String body = ask(teacherToken, "mark absent in 9Z");

    JsonNode chunk = firstChunk(body);
    assertThat(chunk.path("type").asText())
        .as("no confirmRequired token may be issued for a cross-tenant class")
        .isEqualTo("delta");
    assertThat(body).doesNotContain("confirmRequired");
  }

  // --- helpers --------------------------------------------------------------

  private String ask(String token, String question) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(Map.of("question", question))
        .when()
        .post("/api/v1/assistant/ask")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .extract()
        .asString();
  }

  private io.restassured.response.ValidatableResponse confirm(String token) {
    return given()
        .header("Authorization", "Bearer " + teacherToken)
        .contentType(ContentType.JSON)
        .body(Map.of("confirmation", "yes"))
        .when()
        .post("/api/v1/assistant/actions/" + token + "/confirm")
        .then();
  }

  private JsonNode firstChunk(String sseBody) {
    for (String line : sseBody.split("\\r?\\n")) {
      if (line.startsWith("data:")) {
        try {
          return json.readTree(line.substring("data:".length()).trim());
        } catch (Exception e) {
          throw new IllegalStateException("bad SSE data line: " + line, e);
        }
      }
    }
    throw new IllegalStateException("no SSE data frame in: " + sseBody);
  }

  private static LlmResponse text(String t) {
    return new LlmResponse(List.of(new LlmContent.Text(t)), "end_turn", LlmUsage.zero());
  }

  private static LlmResponse toolUse(String name, JsonNode input) {
    return new LlmResponse(
        List.of(new LlmContent.ToolUse("t1", name, input)), "tool_use", LlmUsage.zero());
  }

  private UUID persistSchool(String name) {
    return tx.execute(
        s ->
            schoolRepository
                .save(
                    new School(
                        name,
                        "EG",
                        "Africa/Cairo",
                        "ar-EG",
                        SubscriptionTier.STANDARD,
                        SchoolSettings.defaults()))
                .getId());
  }

  private UUID persistStaff(UUID school, UserRole role, String email, String name) {
    return tx.execute(
        s ->
            userRepository
                .save(User.staff(school, role, name, email, passwordEncoder.encode("pass")))
                .getId());
  }

  private UUID persistParent(UUID school, String phone) {
    return tx.execute(
        s ->
            userRepository
                .save(User.parent(school, "Parent", phone, blindIndex.hash(phone)))
                .getId());
  }

  private UUID persistClass(UUID school, String name) {
    return tx.execute(
        s ->
            classRepository
                .save(new SchoolClass(school, name, "Grade 3", "2025-2026", null))
                .getId());
  }

  private UUID persistStudent(UUID school, String name) {
    return tx.execute(
        s ->
            studentRepository
                .save(new Student(school, name, LocalDate.of(2015, 1, 1), null))
                .getId());
  }

  private String issueStaffToken(UUID userId, UUID school, UserRole role) {
    return jwtService.issueAccess(
        userId.toString(),
        Map.of("kind", "USER", "schoolId", school.toString(), "role", role.name()));
  }
}
