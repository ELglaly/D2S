--liquibase formatted sql

--changeset schoolbridge:013-pgvector-extension
--comment: Enable PGVector for assistant RAG. The target DB role must be able to create the extension, or it must be pre-installed (the changeset is idempotent).
CREATE EXTENSION IF NOT EXISTS vector;
--rollback DROP EXTENSION IF EXISTS vector;

--changeset schoolbridge:013-assistant-documents
--comment: Source-of-truth registry of ingested knowledge documents. One row per source, tenant-scoped, ON DELETE CASCADE to schools per the FK-cascade rule. Vector chunks are not FK-linked here (the stock Spring AI store owns its own table); deletion of chunks is driven from the application by document_id metadata.
CREATE TABLE assistant_documents (
    id          UUID         PRIMARY KEY,
    school_id   UUID         NOT NULL REFERENCES schools(id) ON DELETE CASCADE,
    type        VARCHAR(40)  NOT NULL,        -- KB | FAQ | TOOL_DOC | GUIDE | BUSINESS_RULE | UPLOAD
    title       VARCHAR(300) NOT NULL,
    lang        VARCHAR(8),
    checksum    VARCHAR(64)  NOT NULL,        -- sha-256 of source text; skip re-embed when unchanged
    status      VARCHAR(20)  NOT NULL,        -- PENDING | INDEXED | FAILED
    chunk_count INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_assistant_documents_tenant ON assistant_documents (school_id, type, status);
CREATE UNIQUE INDEX uk_assistant_documents_checksum ON assistant_documents (school_id, checksum);
--rollback DROP TABLE assistant_documents;

--changeset schoolbridge:013-assistant-vector-store
--comment: Chunk + embedding table in the STOCK Spring AI PgVectorStore shape (id, content, metadata, embedding). The store inserts only these four columns, so tenant_id and document_id are carried inside metadata jsonb (metadata.school_id, metadata.document_id). dim=768 matches Vertex text-multilingual-embedding-002 (and the Phase-2 deterministic placeholder embedder). Spring AI runs with initialize-schema=false so Liquibase owns this DDL.
CREATE TABLE assistant_vector_store (
    id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT,
    metadata  JSONB,
    embedding vector(768)
);
--rollback DROP TABLE assistant_vector_store;

--changeset schoolbridge:013-assistant-vector-index
--comment: HNSW cosine index for ANN search; an expression index on metadata.school_id for tenant-scoped retrieval; a GIN index for the remaining metadata filters. HNSW build on an empty table is instant.
CREATE INDEX idx_assistant_vs_hnsw
    ON assistant_vector_store USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_assistant_vs_tenant ON assistant_vector_store ((metadata ->> 'school_id'));
CREATE INDEX idx_assistant_vs_meta   ON assistant_vector_store USING gin (metadata jsonb_path_ops);
--rollback DROP INDEX idx_assistant_vs_meta; DROP INDEX idx_assistant_vs_tenant; DROP INDEX idx_assistant_vs_hnsw;
