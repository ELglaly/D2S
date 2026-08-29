package com.schoolbridge.api.assistant.rag;

import org.springframework.ai.document.Document;

/** A tenant-scoped knowledge-base chunk returned by similarity search, with its source metadata. */
public record RetrievedChunk(String content, String type, String title) {

  public static RetrievedChunk from(Document document) {
    Object type = document.getMetadata().get("type");
    Object title = document.getMetadata().get("title");
    return new RetrievedChunk(
        document.getText(),
        type == null ? null : type.toString(),
        title == null ? null : title.toString());
  }
}

