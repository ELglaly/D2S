package com.schoolbridge.api.assistant.rag.dto;

import com.schoolbridge.api.assistant.rag.DocType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request to ingest one knowledge document into the tenant's RAG corpus. */
public record IngestDocumentRequest(
    @NotNull DocType type,
    @NotBlank @Size(max = 300) String title,
    @Size(max = 8) String lang,
    @NotBlank @Size(max = 200_000) String content) {}

