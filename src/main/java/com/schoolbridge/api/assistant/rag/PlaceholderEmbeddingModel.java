package com.schoolbridge.api.assistant.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic, offline {@link EmbeddingModel} used in Phase 2 so the RAG ingestion pipeline and
 * the PgVectorStore wire and run end-to-end without any cloud embedding provider (which would pull
 * a heavy native stack and require credentials). Vectors are derived purely from the input text —
 * equal text always yields the same unit vector — which makes ingestion deterministic and lets the
 * cross-tenant isolation tests assert exact matches.
 *
 * <p>NOTE: these are NOT semantic embeddings. Phase 3 replaces this bean with the real Vertex
 * {@code text-multilingual-embedding-002} model before retrieval is switched on ({@code
 * rag.enabled=true}).
 */
public class PlaceholderEmbeddingModel implements EmbeddingModel {

  private final int dimensions;

  public PlaceholderEmbeddingModel(int dimensions) {
    this.dimensions = dimensions;
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<Embedding> embeddings = new ArrayList<>();
    List<String> instructions = request.getInstructions();
    for (int i = 0; i < instructions.size(); i++) {
      embeddings.add(new Embedding(vectorFor(instructions.get(i)), i));
    }
    return new EmbeddingResponse(embeddings);
  }

  @Override
  public float[] embed(Document document) {
    return vectorFor(document.getText());
  }

  @Override
  public int dimensions() {
    return dimensions;
  }

  /** A deterministic L2-normalized vector seeded by the sha-256 of the text. */
  private float[] vectorFor(String text) {
    long seed = seed(text == null ? "" : text);
    SplittableRandom random = new SplittableRandom(seed);
    float[] vector = new float[dimensions];
    double sumSquares = 0.0;
    for (int i = 0; i < dimensions; i++) {
      float value = (float) (random.nextDouble() * 2.0 - 1.0);
      vector[i] = value;
      sumSquares += (double) value * value;
    }
    double norm = Math.sqrt(sumSquares);
    if (norm > 0) {
      for (int i = 0; i < dimensions; i++) {
        vector[i] = (float) (vector[i] / norm);
      }
    }
    return vector;
  }

  private static long seed(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      long seed = 0;
      for (int i = 0; i < 8; i++) {
        seed = (seed << 8) | (digest[i] & 0xff);
      }
      return seed;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
