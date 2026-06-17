package com.schoolbridge.api.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.assistant.rag.dto.IngestDocumentRequest;
import com.schoolbridge.api.assistant.rag.dto.KnowledgeDocumentResponse;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end RAG ingestion against a real PGVector container: ingest → chunk → embed → store, with
 * the security-critical assertion that retrieval is strictly tenant-scoped (school A's chunks are
 * invisible under a school B filter).
 */
@SpringBootTest
class RagIngestionIntegrationTest extends AbstractIntegrationTest {

  private static final String CONTENT =
      "The school attendance policy requires parents to acknowledge absence alerts within 24 hours. "
          + "Late arrivals are recorded by the homeroom teacher. Excused absences need a written note "
          + "uploaded through the parent portal before the end of the week.";

  @Autowired DocumentIngestionService ingestion;
  @Autowired KnowledgeDocumentRepository documents;
  @Autowired SchoolRepository schoolRepository;
  @Autowired VectorStore vectorStore;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;

  // Each test gets two freshly-persisted schools with new random ids. Every query/search below is
  // tenant-scoped, so leftover rows from other test classes in the shared (singleton) container can
  // never be seen — no global delete is needed (and deleting all schools would violate the
  // non-cascading users.school_id FK left behind by identity tests).
  @BeforeEach
  void setUp() {
    schoolA = persistSchool("Alpha");
    schoolB = persistSchool("Beta");
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void ingestStoresTenantScopedChunks() {
    TenantContext.set(schoolA);
    KnowledgeDocumentResponse resp = ingestion.ingest(faq(), ctxFor(schoolA));

    assertThat(resp.status()).isEqualTo(IngestStatus.INDEXED);
    assertThat(resp.chunkCount()).isGreaterThanOrEqualTo(1);

    List<Document> hits = search(schoolA);
    assertThat(hits).isNotEmpty();
    assertThat(hits).allMatch(d -> schoolA.toString().equals(d.getMetadata().get("school_id")));
    assertThat(hits).allMatch(d -> resp.id().toString().equals(d.getMetadata().get("document_id")));
  }

  @Test
  void retrievalNeverCrossesTenant() {
    TenantContext.set(schoolA);
    ingestion.ingest(faq(), ctxFor(schoolA));

    assertThat(search(schoolB)).as("school B must not see school A's chunks").isEmpty();
    assertThat(search(schoolA)).isNotEmpty();
  }

  @Test
  void reingestingUnchangedContentIsIdempotent() {
    TenantContext.set(schoolA);
    KnowledgeDocumentResponse first = ingestion.ingest(faq(), ctxFor(schoolA));
    KnowledgeDocumentResponse second = ingestion.ingest(faq(), ctxFor(schoolA));

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(ingestion.list(ctxFor(schoolA))).hasSize(1);
  }

  @Test
  void deleteRemovesDocumentAndChunks() {
    TenantContext.set(schoolA);
    KnowledgeDocumentResponse resp = ingestion.ingest(faq(), ctxFor(schoolA));

    ingestion.delete(resp.id(), ctxFor(schoolA));

    assertThat(ingestion.list(ctxFor(schoolA))).isEmpty();
    assertThat(search(schoolA)).isEmpty();
  }

  // --- helpers --------------------------------------------------------------

  private IngestDocumentRequest faq() {
    return new IngestDocumentRequest(DocType.FAQ, "Attendance FAQ", "en", CONTENT);
  }

  private ToolContext ctxFor(UUID schoolId) {
    return new ToolContext(
        schoolId,
        new StaffPrincipal(UUID.randomUUID(), schoolId, UserRole.SCHOOL_ADMIN),
        UserRole.SCHOOL_ADMIN,
        Locale.ENGLISH,
        null);
  }

  private List<Document> search(UUID schoolId) {
    return vectorStore.similaritySearch(
        SearchRequest.builder()
            .query(CONTENT)
            .topK(20)
            .similarityThreshold(0.0)
            .filterExpression(
                new FilterExpressionBuilder().eq("school_id", schoolId.toString()).build())
            .build());
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
