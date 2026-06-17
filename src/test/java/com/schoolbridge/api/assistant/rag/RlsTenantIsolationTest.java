package com.schoolbridge.api.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
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
 * Verifies the changelog-014 RLS policy AND the {@code set_config('app.current_tenant', …)}
 * plumbing. The migration leaves RLS un-FORCED so the owner role used everywhere else bypasses it;
 * this test FORCEs it for the duration so the owner is also subject, then relies on it being reset
 * in teardown.
 *
 * <p>Under FORCE the {@code WITH CHECK} clause means an ingest INSERT only succeeds if the tenant
 * GUC was bound on the very connection the vector store writes through — so a green ingest proves
 * the binding reaches PgVectorStore's connection, and the cross-tenant read proves the DB blocks
 * leakage independently of the application filter.
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
    jdbc.execute("ALTER TABLE assistant_vector_store FORCE ROW LEVEL SECURITY");
    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");
  }

  @AfterEach
  void tearDown() {
    // Always lift FORCE so the rest of the suite (which relies on owner bypass) is unaffected.
    jdbc.execute("ALTER TABLE assistant_vector_store NO FORCE ROW LEVEL SECURITY");
    TenantContext.clear();
  }

  @Test
  void ingestAndRetrievalSucceedOnlyBecauseTenantGucIsBound() {
    TenantContext.set(schoolA);
    // Under FORCE + WITH CHECK this INSERT throws unless bindTenant reached the write connection.
    ingestion.ingest(faq(), ctxFor(schoolA));

    assertThat(retriever.retrieve(CONTENT, ctxFor(schoolA))).isNotEmpty();
  }

  @Test
  void databaseBlocksCrossTenantRetrieval() {
    TenantContext.set(schoolA);
    ingestion.ingest(faq(), ctxFor(schoolA));

    assertThat(retriever.retrieve(CONTENT, ctxFor(schoolB)))
        .as("RLS must hide school A's chunks from school B")
        .isEmpty();
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
