package com.schoolbridge.api.assistant.rag.dto;

import com.schoolbridge.api.assistant.rag.DocType;
import com.schoolbridge.api.assistant.rag.IngestStatus;
import com.schoolbridge.api.assistant.rag.KnowledgeDocument;
import java.time.Instant;
import java.util.UUID;

/** Tenant-safe view of an ingested knowledge document (no chunk content, no embeddings). */
public record KnowledgeDocumentResponse(
    UUID id,
    DocType type,
    String title,
    String lang,
    IngestStatus status,
    int chunkCount,
    Instant createdAt) {

  public static KnowledgeDocumentResponse from(KnowledgeDocument doc) {
    return new KnowledgeDocumentResponse(
        doc.getId(),
        doc.getType(),
        doc.getTitle(),
        doc.getLang(),
        doc.getStatus(),
        doc.getChunkCount(),
        doc.getCreatedAt());
  }
}
