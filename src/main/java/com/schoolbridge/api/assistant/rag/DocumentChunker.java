package com.schoolbridge.api.assistant.rag;

import com.schoolbridge.api.assistant.llm.AssistantProperties;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

/**
 * Splits raw source text into embeddable chunks using Spring AI's {@link TokenTextSplitter}, sized
 * by {@code schoolbridge.assistant.rag.chunk-size-tokens}. Returns plain strings; the ingestion
 * service wraps each chunk in a {@link Document} with tenant + document metadata.
 */
@Component
public class DocumentChunker {

  private final TokenTextSplitter splitter;

  public DocumentChunker(AssistantProperties properties) {
    this.splitter =
        TokenTextSplitter.builder().withChunkSize(properties.getRag().getChunkSizeTokens()).build();
  }

  /** Splits the text into chunk strings; empty/blank input yields no chunks. */
  public List<String> chunk(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    return splitter.apply(List.of(new Document(text))).stream().map(Document::getText).toList();
  }
}

