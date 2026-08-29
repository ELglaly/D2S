package com.schoolbridge.api.assistant.rag;

import com.schoolbridge.api.assistant.llm.AssistantProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assistant RAG wiring. Supplies the {@link EmbeddingModel} that Spring AI's PgVectorStore
 * auto-configuration requires. In Phase 2 this is the deterministic {@link
 * PlaceholderEmbeddingModel} (no cloud provider, no credentials). It is {@link
 * ConditionalOnMissingBean} so Phase 3 can drop in the real Vertex embedding model simply by
 * defining its own {@code EmbeddingModel} bean.
 */
@Configuration
public class AssistantRagConfig {

  @Bean
  @ConditionalOnMissingBean(EmbeddingModel.class)
  EmbeddingModel placeholderEmbeddingModel(AssistantProperties properties) {
    return new PlaceholderEmbeddingModel(properties.getRag().getEmbeddingDim());
  }
}

