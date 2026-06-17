package com.schoolbridge.api.assistant.rag;

import com.schoolbridge.api.assistant.llm.AssistantProperties;
import com.schoolbridge.api.assistant.tools.ToolContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single, centralized entry point for RAG retrieval. Every similarity search is hard-scoped to
 * the caller's tenant ({@code metadata.school_id == ctx.schoolId()}) so a missed filter can never
 * leak another tenant's documents — keeping the tenant predicate in one auditable place is the
 * primary isolation control. As defense-in-depth, the tenant is also bound to the transaction via
 * {@code set_config('app.current_tenant', …)} so the database RLS policy (changelog 014) enforces
 * isolation independently. Returns empty when RAG is disabled, the query is blank, or the search
 * fails: retrieval is best-effort and must never break the chat flow.
 */
@Component
public class RagRetriever {

  private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);

  private final VectorStore vectorStore;
  private final AssistantProperties properties;
  private final JdbcTemplate jdbcTemplate;

  public RagRetriever(
      VectorStore vectorStore, AssistantProperties properties, JdbcTemplate jdbcTemplate) {
    this.vectorStore = vectorStore;
    this.properties = properties;
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Top-k tenant-scoped chunks for the query, or empty if RAG is off / blank / on failure. */
  @Transactional(readOnly = true)
  public List<RetrievedChunk> retrieve(String query, ToolContext ctx) {
    AssistantProperties.Rag rag = properties.getRag();
    if (!rag.isEnabled() || query == null || query.isBlank()) {
      return List.of();
    }
    try {
      // Bind the tenant for the RLS policy on the same transaction/connection the vector store
      // uses.
      jdbcTemplate.queryForObject(
          "select set_config('app.current_tenant', ?, true)",
          String.class,
          ctx.schoolId().toString());
      Filter.Expression tenantFilter =
          new FilterExpressionBuilder().eq("school_id", ctx.schoolId().toString()).build();
      SearchRequest request =
          SearchRequest.builder()
              .query(query)
              .topK(rag.getTopK())
              .similarityThreshold(rag.getMinScore())
              .filterExpression(tenantFilter)
              .build();
      return vectorStore.similaritySearch(request).stream().map(RetrievedChunk::from).toList();
    } catch (RuntimeException e) {
      log.warn("RAG retrieval failed for school {}; continuing without context", ctx.schoolId(), e);
      return List.of();
    }
  }
}
