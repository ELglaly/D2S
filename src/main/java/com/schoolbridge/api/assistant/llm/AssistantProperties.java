package com.schoolbridge.api.assistant.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the AI assistant module ({@code schoolbridge.assistant.*}). Ships dark: {@code
 * enabled=false} keeps the LLM beans unloaded, and {@code actions.enabled=false} keeps mutating
 * tools off even when reads are live.
 *
 * <p>There is no {@code engine} or {@code provider} property and no API key here. Spring AI is the
 * only engine (ADR-007) and the provider and its credentials are configured under {@code
 * spring.ai.*} — keeping exactly one home for the key, so there is only one place a secret can be
 * committed by accident.
 */
@ConfigurationProperties(prefix = "schoolbridge.assistant")
public class AssistantProperties {

  private boolean enabled = false;
  private String model = "deepseek-ai/deepseek-v4-flash";
  private int maxToolIterations = 4;
  private Duration requestTimeout = Duration.ofSeconds(30);
  private long maxTokens = 1024;
  private Duration readCacheTtl = Duration.ofHours(24);
  private int maxQuestionLength = 500;
  private int rateLimitPerMinute = 20;
  private int maxHistoryMessages = 40;
  private int toolResultMaxItems = 50;
  private boolean toolGatingEnabled = true;
  private String defaultSystemPrompt = "";

  private final Actions actions = new Actions();
  private final Rag rag = new Rag();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public int getMaxToolIterations() {
    return maxToolIterations;
  }

  public void setMaxToolIterations(int maxToolIterations) {
    this.maxToolIterations = maxToolIterations;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  public long getMaxTokens() {
    return maxTokens;
  }

  public void setMaxTokens(long maxTokens) {
    this.maxTokens = maxTokens;
  }

  public Duration getReadCacheTtl() {
    return readCacheTtl;
  }

  public void setReadCacheTtl(Duration readCacheTtl) {
    this.readCacheTtl = readCacheTtl;
  }

  public int getMaxQuestionLength() {
    return maxQuestionLength;
  }

  public void setMaxQuestionLength(int maxQuestionLength) {
    this.maxQuestionLength = maxQuestionLength;
  }

  public int getRateLimitPerMinute() {
    return rateLimitPerMinute;
  }

  public void setRateLimitPerMinute(int rateLimitPerMinute) {
    this.rateLimitPerMinute = rateLimitPerMinute;
  }

  public int getMaxHistoryMessages() {
    return maxHistoryMessages;
  }

  public void setMaxHistoryMessages(int maxHistoryMessages) {
    this.maxHistoryMessages = maxHistoryMessages;
  }

  public int getToolResultMaxItems() {
    return toolResultMaxItems;
  }

  public void setToolResultMaxItems(int toolResultMaxItems) {
    this.toolResultMaxItems = toolResultMaxItems;
  }

  public boolean isToolGatingEnabled() {
    return toolGatingEnabled;
  }

  public void setToolGatingEnabled(boolean toolGatingEnabled) {
    this.toolGatingEnabled = toolGatingEnabled;
  }

  public String getDefaultSystemPrompt() {
    return defaultSystemPrompt;
  }

  public void setDefaultSystemPrompt(String defaultSystemPrompt) {
    this.defaultSystemPrompt = defaultSystemPrompt;
  }

  public Actions getActions() {
    return actions;
  }

  public Rag getRag() {
    return rag;
  }

  /** Action-layer (mutation) settings — a second kill-switch plus the confirmation gate knobs. */
  public static class Actions {

    private boolean enabled = false;
    private Duration confirmationTtl = Duration.ofMinutes(5);
    private boolean destructiveRequireTypedConfirm = true;
    private int maxBulkImpact = 200;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public Duration getConfirmationTtl() {
      return confirmationTtl;
    }

    public void setConfirmationTtl(Duration confirmationTtl) {
      this.confirmationTtl = confirmationTtl;
    }

    public boolean isDestructiveRequireTypedConfirm() {
      return destructiveRequireTypedConfirm;
    }

    public void setDestructiveRequireTypedConfirm(boolean destructiveRequireTypedConfirm) {
      this.destructiveRequireTypedConfirm = destructiveRequireTypedConfirm;
    }

    public int getMaxBulkImpact() {
      return maxBulkImpact;
    }

    public void setMaxBulkImpact(int maxBulkImpact) {
      this.maxBulkImpact = maxBulkImpact;
    }
  }

  /**
   * Retrieval-Augmented Generation settings ({@code schoolbridge.assistant.rag.*}). Ships dark:
   * {@code enabled=false} keeps retrieval out of the chat flow (wired in Phase 3) while ingestion
   * and the vector store still load so documents can be indexed ahead of go-live. {@code
   * embeddingDim} must equal the {@code assistant_vector_store.embedding} {@code vector(N)} column.
   */
  public static class Rag {

    private boolean enabled = false;
    private int topK = 5;
    private double minScore = 0.65;
    private int maxContextChars = 6000;
    private int embeddingDim = 768;
    private int chunkSizeTokens = 600;
    private int chunkOverlapTokens = 80;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getTopK() {
      return topK;
    }

    public void setTopK(int topK) {
      this.topK = topK;
    }

    public double getMinScore() {
      return minScore;
    }

    public void setMinScore(double minScore) {
      this.minScore = minScore;
    }

    public int getMaxContextChars() {
      return maxContextChars;
    }

    public void setMaxContextChars(int maxContextChars) {
      this.maxContextChars = maxContextChars;
    }

    public int getEmbeddingDim() {
      return embeddingDim;
    }

    public void setEmbeddingDim(int embeddingDim) {
      this.embeddingDim = embeddingDim;
    }

    public int getChunkSizeTokens() {
      return chunkSizeTokens;
    }

    public void setChunkSizeTokens(int chunkSizeTokens) {
      this.chunkSizeTokens = chunkSizeTokens;
    }

    public int getChunkOverlapTokens() {
      return chunkOverlapTokens;
    }

    public void setChunkOverlapTokens(int chunkOverlapTokens) {
      this.chunkOverlapTokens = chunkOverlapTokens;
    }
  }
}
