package com.schoolbridge.api.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.RlsTestRole;
import com.schoolbridge.api.assistant.rag.dto.IngestDocumentRequest;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the changelog-014 RLS policy on {@code assistant_vector_store} AND the {@link
 * com.schoolbridge.api.common.tenancy.TenantSessionBinder} plumbing that feeds it.
 *
 * <p><b>Why every case runs inside a transaction under {@link RlsTestRole}.</b> The migration
 * leaves RLS un-FORCEd so the owner bypasses it, and Testcontainers connects as the bootstrap
 * superuser, which bypasses RLS whether or not the table is FORCEd. An earlier version of this
 * class used {@code ALTER TABLE … FORCE ROW LEVEL SECURITY} and asserted on the default connection;
 * that assertion held because of the application-side metadata filter, and would have held with the
 * policy dropped. Running as an unprivileged role is what makes these assertions about the
 * database.
 *
 * <p>Ingestion and retrieval are {@code @Transactional}, so calling them inside {@link
 * TransactionTemplate} makes them join the transaction that carries the {@code SET LOCAL ROLE} —
 * the same connection the policy is evaluated on.
 */
@SpringBootTest(
    properties = {
      "schoolbridge.assistant.rag.enabled=true",
      "schoolbridge.assistant.rag.min-score=0.0"
    })
class RlsTenantIsolationTest extends AbstractIntegrationTest {

  private static final String CONTENT =
      "Field trips require a signed permission slip returned to the homeroom teacher by Thursday.";

  @Autowired DocumentIngestionService ingestion;
  @Autowired RagRetriever retriever;
  @Autowired SchoolRepository schoolRepository;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;

  @BeforeEach
  void setUp() {
    RlsTestRole.ensureExists(jdbc);
    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void ingestAndRetrievalSucceedOnlyBecauseTenantGucIsBound() {
    TenantContext.set(schoolA);

    tx.executeWithoutResult(
        status -> {
          jdbc.execute(RlsTestRole.ASSUME);
          // WITH CHECK rejects this INSERT unless bindTenant reached the write connection.
          ingestion.ingest(faq(), ctxFor(schoolA));
          assertThat(retriever.retrieve(CONTENT, ctxFor(schoolA))).isNotEmpty();
        });
  }

  @Test
  void databaseHidesAnotherTenantsChunksFromARawQuery() {
    ingestAsSchoolA();

    Long visibleToB = countChunksAs(schoolB);
    Long visibleToA = countChunksAs(schoolA);

    assertThat(visibleToB).as("school B must not see school A's chunks").isZero();
    assertThat(visibleToA).as("the policy must not hide a tenant's own chunks").isPositive();
  }

  @Test
  void unboundTenantSeesNoChunksRatherThanEveryTenantsChunks() {
    ingestAsSchoolA();

    Long visible =
        tx.execute(
            status -> {
              jdbc.execute(RlsTestRole.ASSUME);
              return jdbc.queryForObject("select count(*) from assistant_vector_store", Long.class);
            });

    assertThat(visible).as("an unbound tenant GUC must fail closed").isZero();
  }

  @Test
  void retrievalReturnsNothingForAnotherTenant() {
    ingestAsSchoolA();

    tx.executeWithoutResult(
        status -> {
          jdbc.execute(RlsTestRole.ASSUME);
          assertThat(retriever.retrieve(CONTENT, ctxFor(schoolB)))
              .as("school B's retrieval must surface none of school A's chunks")
              .isEmpty();
        });
  }

  /** Raw count, going around the retriever's metadata filter so only RLS can be doing the work. */
  private Long countChunksAs(UUID schoolId) {
    return tx.execute(
        status -> {
          jdbc.execute(RlsTestRole.ASSUME);
          jdbc.queryForObject(
              "select set_config('app.current_tenant', ?, true)",
              String.class,
              schoolId.toString());
          return jdbc.queryForObject("select count(*) from assistant_vector_store", Long.class);
        });
  }

  private void ingestAsSchoolA() {
    TenantContext.set(schoolA);
    ingestion.ingest(faq(), ctxFor(schoolA));
    TenantContext.clear();
  }

  private IngestDocumentRequest faq() {
    return new IngestDocumentRequest(DocType.GUIDE, "Field trips", "en", CONTENT);
  }

  private ToolContext ctxFor(UUID schoolId) {
    return new ToolContext(
        schoolId,
        new StaffPrincipal(UUID.randomUUID(), schoolId, UserRole.SCHOOL_ADMIN),
        UserRole.SCHOOL_ADMIN,
        Locale.ENGLISH,
        null);
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
}
