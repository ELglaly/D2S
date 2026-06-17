package com.schoolbridge.api.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.assistant.llm.AssistantProperties;
import org.junit.jupiter.api.Test;

class DocumentChunkerTest {

  private final DocumentChunker chunker = new DocumentChunker(new AssistantProperties());

  @Test
  void emptyOrBlankYieldsNoChunks() {
    assertThat(chunker.chunk(null)).isEmpty();
    assertThat(chunker.chunk("   ")).isEmpty();
  }

  @Test
  void shortTextYieldsAtLeastOneChunk() {
    var chunks = chunker.chunk("Homework is due on Sunday. Parents must acknowledge it.");
    assertThat(chunks).isNotEmpty();
    assertThat(String.join(" ", chunks)).contains("Homework");
  }

  @Test
  void longTextSplitsIntoMultipleChunks() {
    String sentence = "The school attendance policy requires parents to acknowledge alerts. ";
    String longText = sentence.repeat(400); // well over the default 600-token chunk size
    assertThat(chunker.chunk(longText)).hasSizeGreaterThan(1);
  }
}
