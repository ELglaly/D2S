package com.schoolbridge.api.assistant.rag;

import com.schoolbridge.api.assistant.rag.dto.IngestDocumentRequest;
import com.schoolbridge.api.assistant.rag.dto.KnowledgeDocumentResponse;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.NotFoundException;
import com.schoolbridge.api.common.tenancy.TenantSessionBinder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped ingestion of knowledge documents into the RAG corpus: chunk â†’ embed â†’ store. The
 * registry row ({@link KnowledgeDocument}) and the vector chunks are always scoped to {@code
 * ctx.schoolId()} (never a client-supplied tenant); each chunk carries {@code school_id} and {@code
 * document_id} in its vector-store metadata so retrieval can filter by tenant and deletes can
 * target a single document. Re-ingesting unchanged content (same sha-256) is a no-op.
 */
@Service
public class DocumentIngestionService {

  private final KnowledgeDocumentRepository documents;
  private final VectorStore vectorStore;
  private final DocumentChunker chunker;
  private final AuditService audit;
  private final TenantSessionBinder sessionBinder;

  public DocumentIngestionService(
      KnowledgeDocumentRepository documents,
      VectorStore vectorStore,
      DocumentChunker chunker,
      AuditService audit,
      TenantSessionBinder sessionBinder) {
    this.documents = documents;
    this.vectorStore = vectorStore;
    this.chunker = chunker;
    this.audit = audit;
    this.sessionBinder = sessionBinder;
  }

  /**
   * Binds the tenant for the vector-store RLS policy on the current transaction (changelog 014).
   */
  private void bindTenant(ToolContext ctx) {
    sessionBinder.bindTenant(ctx.schoolId());
  }

  /** Ingests (or idempotently skips) one document for the caller's tenant. */
  @Transactional
  public KnowledgeDocumentResponse ingest(IngestDocumentRequest request, ToolContext ctx) {
    bindTenant(ctx);
    String checksum = sha256(request.content());
    Optional<KnowledgeDocument> existing =
        documents.findBySchoolAndChecksum(ctx.schoolId(), checksum);
    if (existing.isPresent() && existing.get().getStatus() == IngestStatus.INDEXED) {
      return KnowledgeDocumentResponse.from(existing.get());
    }

    KnowledgeDocument doc =
        existing.orElseGet(
            () ->
                new KnowledgeDocument(
                    ctx.schoolId(), request.type(), request.title(), request.lang(), checksum));
    doc = documents.save(doc);

    // Replace any chunks from a prior failed/partial attempt before re-embedding.
    vectorStore.delete(byDocument(doc.getId()));

    List<String> chunks = chunker.chunk(request.content());
    List<Document> embeddable = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      embeddable.add(new Document(chunks.get(i), chunkMetadata(ctx, doc, i)));
    }
    if (!embeddable.isEmpty()) {
      vectorStore.add(embeddable);
    }

    doc.markIndexed(chunks.size());
    documents.save(doc);
    record(ctx, "assistant.rag.ingest", doc);
    return KnowledgeDocumentResponse.from(doc);
  }

  /** All documents in the caller's tenant, newest first. */
  @Transactional(readOnly = true)
  public List<KnowledgeDocumentResponse> list(ToolContext ctx) {
    return documents.findBySchool(ctx.schoolId()).stream()
        .map(KnowledgeDocumentResponse::from)
        .toList();
  }

  /**
   * Deletes a document and its chunks. Foreign-tenant or missing ids are indistinguishable (404).
   */
  @Transactional
  public void delete(UUID documentId, ToolContext ctx) {
    bindTenant(ctx);
    KnowledgeDocument doc =
        documents
            .findById(documentId)
            .filter(d -> d.getSchoolId().equals(ctx.schoolId()))
            .orElseThrow(NotFoundException::new);
    vectorStore.delete(byDocument(documentId));
    documents.delete(doc);
    record(ctx, "assistant.rag.delete", doc);
  }

  private Filter.Expression byDocument(UUID documentId) {
    return new FilterExpressionBuilder().eq("document_id", documentId.toString()).build();
  }

  private Map<String, Object> chunkMetadata(
      ToolContext ctx, KnowledgeDocument doc, int chunkIndex) {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("school_id", ctx.schoolId().toString());
    metadata.put("document_id", doc.getId().toString());
    metadata.put("type", doc.getType().name());
    metadata.put("title", doc.getTitle());
    if (doc.getLang() != null) {
      metadata.put("lang", doc.getLang());
    }
    metadata.put("chunk_index", chunkIndex);
    return metadata;
  }

  private void record(ToolContext ctx, String action, KnowledgeDocument doc) {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("type", doc.getType().name());
    metadata.put("chunkCount", doc.getChunkCount());
    audit.record(ctx.schoolId(), ctx.userId(), action, "AssistantDocument", doc.getId(), metadata);
  }

  private static String sha256(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}

