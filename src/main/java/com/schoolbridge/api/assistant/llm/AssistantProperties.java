package com.schoolbridge.api.assistant.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the AI assistant module ({@code schoolbridge.assistant.*}). Ships dark: {@code
 * enabled=false} keeps the LLM beans unloaded, and {@code actions.enabled=false} keeps mutating
 * tools off even when reads are live. The API key is env-only and validated at startup when
 * enabled.
 */
@ConfigurationProperties(prefix = "schoolbridge.assistant")
public class AssistantProperties {

  private boolean enabled = false;
  private String engine = "native";
  private String provider = "anthropic";
  private String apiKey = "";
  private String geminiApiKey = "";
  private String deepseekApiKey = "";
  private String deepseekBaseUrl = "https://integrate.api.nvidia.com/v1";
  private String model = "claude-haiku-4-5-20251001";
  private int maxToolIterations = 4;
  private Duration requestTimeout = Duration.ofSeconds(30);
  private long maxTokens = 1024;
  private Duration readCacheTtl = Duration.ofHours(24);
  private int maxQuestionLength = 500;
  private int rateLimitPerMinute = 20;
  private int maxHistoryMessages = 40;
  private String defaultSystemPrompt = "";

  private final Actions actions = new Actions();
  private final Rag rag = new Rag();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getEngine() {
    return engine;
  }

  public void setEngine(String engine) {
    this.engine = engine;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getGeminiApiKey() {
    return geminiApiKey;
  }

  public void setGeminiApiKey(String geminiApiKey) {
    this.geminiApiKey = geminiApiKey;
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

  public String getDeepseekApiKey() {
    return deepseekApiKey;
  }

  public void setDeepseekApiKey(String deepseekApiKey) {
    this.deepseekApiKey = deepseekApiKey;
  }

  public String getDeepseekBaseUrl() {
    return deepseekBaseUrl;
  }

  public void setDeepseekBaseUrl(String deepseekBaseUrl) {
    this.deepseekBaseUrl = deepseekBaseUrl;
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
