package com.schoolbridge.api.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.assistant.llm.AssistantProperties;
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
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retrieval is tenant-scoped and respects the enabled flag. Runs with {@code rag.enabled=true} and
 * {@code min-score=0.0} so the (non-semantic) placeholder embeddings don't drop tenant matches by
 * score; isolation is enforced by the metadata filter, not by similarity.
 */
@SpringBootTest(
    properties = {
      "schoolbridge.assistant.rag.enabled=true",
      "schoolbridge.assistant.rag.min-score=0.0"
    })
class RagRetrieverTest extends AbstractIntegrationTest {

  private static final String CONTENT =
      "Excused absences need a written note uploaded through the parent portal before the week ends.";

  @Autowired DocumentIngestionService ingestion;
  @Autowired RagRetriever retriever;
  @Autowired VectorStore vectorStore;
  @Autowired SchoolRepository schoolRepository;
  @Autowired TransactionTemplate tx;

  private UUID schoolA;
  private UUID schoolB;

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
  void retrievesOwnTenantChunksOnly() {
    TenantContext.set(schoolA);
    ingestion.ingest(faq(), ctxFor(schoolA));

    assertThat(retriever.retrieve(CONTENT, ctxFor(schoolA))).isNotEmpty();
    assertThat(retriever.retrieve(CONTENT, ctxFor(schoolB)))
        .as("school B must not retrieve school A's chunks")
        .isEmpty();
  }

  @Test
  void returnsEmptyWhenRagDisabled() {
    TenantContext.set(schoolA);
    ingestion.ingest(faq(), ctxFor(schoolA));

    AssistantProperties disabled = new AssistantProperties(); // rag.enabled defaults to false
    RagRetriever offRetriever = new RagRetriever(vectorStore, disabled, null);

    assertThat(offRetriever.retrieve(CONTENT, ctxFor(schoolA))).isEmpty();
  }

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
