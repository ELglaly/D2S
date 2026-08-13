package com.schoolbridge.api.attachments;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.jwt.JwtService;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end attachment pipeline against a real MinIO container: presign, PUT the bytes the way a
 * client would, complete, download.
 *
 * <p>The uploads here go straight to the container over HTTP rather than through this API, because
 * that is the actual design. A test that posted bytes to the API would be exercising something the
 * product deliberately does not do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttachmentPipelineIntegrationTest extends AbstractIntegrationTest {

  private static final byte[] PNG_BYTES = {
    (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03
  };
  private static final byte[] PDF_BYTES =
      "%PDF-1.7 not really a pdf".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] EXE_BYTES = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x04, 0x00};

  /** What {@code POST /attachments} hands back: the row id and the URL to PUT to. */
  private record Ticket(UUID id, String uploadUrl) {}

  @LocalServerPort int port;

  @Autowired AttachmentRepository attachmentRepository;
  @Autowired UserRepository userRepository;
  @Autowired SchoolRepository schoolRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JwtService jwtService;
  @Autowired TransactionTemplate tx;
  @Autowired StorageProperties storageProperties;

  private UUID schoolA;
  private UUID schoolB;
  private String tokenA;
  private String tokenB;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    // Cleanup must run unscoped. A tenant left bound on this thread by an earlier test would
    // activate the Hibernate filter and quietly delete only that school's rows, and the next
    // `delete from schools` would then fail on the survivors' foreign key.
    TenantContext.clear();
    tx.executeWithoutResult(s -> attachmentRepository.deleteAll());
    tx.executeWithoutResult(s -> userRepository.deleteAll());
    tx.executeWithoutResult(s -> schoolRepository.deleteAll());

    schoolA = createSchool("Alpha Academy");
    schoolB = createSchool("Beta School");
    tokenA = issueStaffToken(createAdmin(schoolA, "admin.a@attach.test"), schoolA);
    tokenB = issueStaffToken(createAdmin(schoolB, "admin.b@attach.test"), schoolB);
  }

  @Test
  void uploadsAndDownloadsAPngEndToEnd() throws Exception {
    Ticket ticket = requestUpload(tokenA, "photo.png", "image/png", PNG_BYTES.length);
    putBytes(ticket.uploadUrl(), PNG_BYTES, "image/png");

    given()
        .header("Authorization", "Bearer " + tokenA)
        .post("/api/v1/attachments/" + ticket.id() + "/complete")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("data.status", equalTo("CLEAN"))
        .body("data.contentType", equalTo("image/png"))
        .body("data.sizeBytes", equalTo(PNG_BYTES.length))
        // Scanning is off in the test profile. SKIPPED must stay distinguishable from CLEAN, or a
        // later audit cannot tell which objects were ever actually inspected.
        .body("data.avResult", equalTo("SKIPPED"));

    String downloadUrl =
        given()
            .header("Authorization", "Bearer " + tokenA)
            .get("/api/v1/attachments/" + ticket.id() + "/download")
            .then()
            .statusCode(200)
            .body("data.downloadUrl", notNullValue())
            .extract()
            .path("data.downloadUrl");

    assertThat(fetch(downloadUrl)).isEqualTo(PNG_BYTES);
  }

  @Test
  void rejectsAFileWhoseContentDisagreesWithItsDeclaredType() throws Exception {
    // The upload the sniffing step exists for: named and declared as a PDF, actually a PE binary.
    Ticket ticket = requestUpload(tokenA, "invoice.pdf", "application/pdf", EXE_BYTES.length);
    putBytes(ticket.uploadUrl(), EXE_BYTES, "application/pdf");

    given()
        .header("Authorization", "Bearer " + tokenA)
        .post("/api/v1/attachments/" + ticket.id() + "/complete")
        .then()
        .statusCode(200)
        .body("data.status", equalTo("REJECTED"));

    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/v1/attachments/" + ticket.id() + "/download")
        .then()
        .statusCode(409);
  }

  @Test
  void rejectsADeclaredTypeThatIsNotOnTheAllowList() {
    given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body(Map.of("fileName", "notes.txt", "contentType", "text/plain", "sizeBytes", 12))
        .post("/api/v1/attachments")
        .then()
        .statusCode(422);

    assertThat(countAttachments()).isZero();
  }

  @Test
  void rejectsADeclaredSizeOverTheCapBeforeIssuingAnyUrl() {
    long overCap = storageProperties.getMaxUploadBytes() + 1;

    given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body(Map.of("fileName", "huge.png", "contentType", "image/png", "sizeBytes", overCap))
        .post("/api/v1/attachments")
        .then()
        .statusCode(422);

    // No row means nothing for the sweeper to clean up either.
    assertThat(countAttachments()).isZero();
  }

  @Test
  void refusesToDownloadBeforeTheUploadIsCompleted() {
    Ticket ticket = requestUpload(tokenA, "photo.png", "image/png", PNG_BYTES.length);

    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/v1/attachments/" + ticket.id() + "/download")
        .then()
        .statusCode(409);
  }

  @Test
  void refusesToCompleteWhenNoBytesWereEverUploaded() {
    Ticket ticket = requestUpload(tokenA, "photo.png", "image/png", PNG_BYTES.length);

    given()
        .header("Authorization", "Bearer " + tokenA)
        .post("/api/v1/attachments/" + ticket.id() + "/complete")
        .then()
        .statusCode(409);
  }

  @Test
  void refusesToCompleteTwice() throws Exception {
    Ticket ticket = requestUpload(tokenA, "doc.pdf", "application/pdf", PDF_BYTES.length);
    putBytes(ticket.uploadUrl(), PDF_BYTES, "application/pdf");

    given()
        .header("Authorization", "Bearer " + tokenA)
        .post("/api/v1/attachments/" + ticket.id() + "/complete")
        .then()
        .statusCode(200);
    given()
        .header("Authorization", "Bearer " + tokenA)
        .post("/api/v1/attachments/" + ticket.id() + "/complete")
        .then()
        .statusCode(409);
  }

  @Test
  void anotherSchoolCannotReadDownloadOrDeleteAnAttachment() throws Exception {
    Ticket ticket = requestUpload(tokenA, "photo.png", "image/png", PNG_BYTES.length);
    putBytes(ticket.uploadUrl(), PNG_BYTES, "image/png");
    given()
        .header("Authorization", "Bearer " + tokenA)
        .post("/api/v1/attachments/" + ticket.id() + "/complete")
        .then()
        .statusCode(200);

    // School B holds a perfectly valid admin token — for its own tenant. 404 rather than 403 on
    // purpose: the existence of another school's attachment is itself not disclosed.
    given()
        .header("Authorization", "Bearer " + tokenB)
        .get("/api/v1/attachments/" + ticket.id())
        .then()
        .statusCode(404);
    given()
        .header("Authorization", "Bearer " + tokenB)
        .get("/api/v1/attachments/" + ticket.id() + "/download")
        .then()
        .statusCode(404);
    given()
        .header("Authorization", "Bearer " + tokenB)
        .delete("/api/v1/attachments/" + ticket.id())
        .then()
        .statusCode(404);

    assertThat(countAttachments()).isEqualTo(1);
  }

  @Test
  void storesObjectsUnderTheOwningSchoolsKeyPrefix() {
    Ticket ticket = requestUpload(tokenA, "photo.png", "image/png", PNG_BYTES.length);

    String key =
        TenantContext.runAs(
            schoolA,
            () ->
                tx.execute(
                    s -> attachmentRepository.findById(ticket.id()).orElseThrow().getStorageKey()));

    assertThat(key).startsWith(schoolA + "/");
    assertThat(AttachmentKeys.belongsToSchool(key, schoolA)).isTrue();
    assertThat(AttachmentKeys.belongsToSchool(key, schoolB)).isFalse();
  }

  @Test
  void deletesObjectAndRow() throws Exception {
    Ticket ticket = requestUpload(tokenA, "doc.pdf", "application/pdf", PDF_BYTES.length);
    putBytes(ticket.uploadUrl(), PDF_BYTES, "application/pdf");
    given()
        .header("Authorization", "Bearer " + tokenA)
        .post("/api/v1/attachments/" + ticket.id() + "/complete")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + tokenA)
        .delete("/api/v1/attachments/" + ticket.id())
        .then()
        .statusCode(204);

    assertThat(countAttachments()).isZero();
  }

  // --- helpers -------------------------------------------------------------------------------

  private Ticket requestUpload(String token, String fileName, String contentType, long size) {
    var response =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(Map.of("fileName", fileName, "contentType", contentType, "sizeBytes", size))
            .post("/api/v1/attachments")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)
            .body("data.uploadUrl", notNullValue())
            .body("data.method", equalTo("PUT"))
            .extract();
    return new Ticket(
        UUID.fromString(response.path("data.attachmentId")), response.path("data.uploadUrl"));
  }

  /** The client's half of the upload: a plain PUT to the presigned URL, no API involvement. */
  private void putBytes(String uploadUrl, byte[] body, String contentType) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) URI.create(uploadUrl).toURL().openConnection();
    connection.setRequestMethod("PUT");
    connection.setDoOutput(true);
    // Sends Content-Length rather than chunked encoding — the length is part of the signature.
    connection.setFixedLengthStreamingMode(body.length);
    connection.setRequestProperty("Content-Type", contentType);
    try (OutputStream out = connection.getOutputStream()) {
      out.write(body);
    }
    int status = connection.getResponseCode();
    connection.disconnect();
    assertThat(status).isBetween(200, 299);
  }

  private byte[] fetch(String url) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
    connection.setRequestMethod("GET");
    try (InputStream in = connection.getInputStream()) {
      return in.readAllBytes();
    } finally {
      connection.disconnect();
    }
  }

  private long countAttachments() {
    return tx.execute(s -> attachmentRepository.count());
  }

  private UUID createSchool(String name) {
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

  private UUID createAdmin(UUID schoolId, String email) {
    return tx.execute(
        s ->
            userRepository
                .save(
                    User.staff(
                        schoolId,
                        UserRole.SCHOOL_ADMIN,
                        "Admin",
                        email,
                        passwordEncoder.encode("pass")))
                .getId());
  }

  private String issueStaffToken(UUID userId, UUID schoolId) {
    return jwtService.issueAccess(
        userId.toString(),
        Map.of(
            "kind", "USER", "schoolId", schoolId.toString(), "role", UserRole.SCHOOL_ADMIN.name()));
  }
}
