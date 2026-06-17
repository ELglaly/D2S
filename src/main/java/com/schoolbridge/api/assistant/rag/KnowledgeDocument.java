package com.schoolbridge.api.assistant.rag;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Source-of-truth registry row for one ingested knowledge document within a tenant. The chunk
 * embeddings live in the stock Spring AI {@code assistant_vector_store} (keyed by this document's
 * id in chunk metadata), not here. {@code checksum} is the sha-256 of the source text so
 * re-ingesting unchanged content is a no-op.
 */
@Entity
@Table(name = "assistant_documents")
public class KnowledgeDocument extends TenantEntity {

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 40)
  private DocType type;

  @Column(nullable = false, length = 300)
  private String title;

  @Column(length = 8)
  private String lang;

  @Column(nullable = false, length = 64)
  private String checksum;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private IngestStatus status;

  @Column(name = "chunk_count", nullable = false)
  private int chunkCount;

  protected KnowledgeDocument() {}

  public KnowledgeDocument(
      UUID schoolId, DocType type, String title, String lang, String checksum) {
    super(schoolId);
    this.type = type;
    this.title = title;
    this.lang = lang;
    this.checksum = checksum;
    this.status = IngestStatus.PENDING;
    this.chunkCount = 0;
  }

  /** Marks the document successfully embedded with the given number of chunks. */
  public void markIndexed(int chunkCount) {
    this.status = IngestStatus.INDEXED;
    this.chunkCount = chunkCount;
  }

  public void markFailed() {
    this.status = IngestStatus.FAILED;
  }

  public DocType getType() {
    return type;
  }

  public String getTitle() {
    return title;
  }

  public String getLang() {
    return lang;
  }

  public String getChecksum() {
    return checksum;
  }

  public IngestStatus getStatus() {
    return status;
  }

  public int getChunkCount() {
    return chunkCount;
  }
}
