package com.schoolbridge.api.attachments;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code schoolbridge.storage.*} â€” the object store behind the attachment
 * pipeline, plus the two controls that make an upload safe to serve back (the MIME allow-list and
 * AV scanning).
 *
 * <p>{@code accessKey} / {@code secretKey} carry no defaults on purpose, exactly as the crypto keys
 * do: a default would let a profile that forgot to override it start against someone else's bucket
 * or, worse, appear to work while writing nowhere.
 */
@ConfigurationProperties(prefix = "schoolbridge.storage")
public class StorageProperties {

  /**
   * S3-compatible endpoint. Blank means "real AWS S3, resolve the endpoint from the region"; set it
   * for MinIO, R2, or any other implementation.
   */
  private String endpoint = "";

  private String region = "us-east-1";

  private String bucket = "schoolbridge-attachments";

  private String accessKey;

  private String secretKey;

  /**
   * Path-style addressing ({@code host/bucket/key}) rather than virtual-host style ({@code
   * bucket.host/key}). Required by MinIO on a bare host or IP, which has no wildcard DNS to make
   * the virtual-host form resolve.
   */
  private boolean pathStyleAccess = true;

  /**
   * Hard cap on a single object, enforced twice: the declared size is rejected here before a URL is
   * ever minted, and the same value is signed into the PUT as {@code Content-Length} so the object
   * store rejects a body that disagrees. See {@code docs/PLAN_FILE_UPLOAD.md} section 2.2 â€” a
   * presigned PUT cannot carry a content-length-range condition, so signing the exact length is the
   * enforcement mechanism.
   */
  private long maxUploadBytes = 10L * 1024 * 1024;

  /** How long a client has to complete the PUT it was handed a URL for. */
  private Duration uploadUrlTtl = Duration.ofMinutes(10);

  /**
   * Download URL lifetime. Short on purpose: the URL is a bearer credential for a student's photo
   * and it survives being pasted into a chat, so it should expire before it can be shared usefully.
   */
  private Duration downloadUrlTtl = Duration.ofMinutes(5);

  /**
   * Content types a client may upload. Enforced against the type sniffed from the stored bytes, not
   * against the client's declaration â€” the declaration only has to agree with it.
   */
  private List<String> allowedContentTypes =
      List.of("image/jpeg", "image/png", "image/webp", "application/pdf");

  /** Bytes read from the head of the object for magic-byte sniffing. */
  private int sniffBytes = 4096;

  private final Av av = new Av();

  private final Sweeper sweeper = new Sweeper();

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public String getRegion() {
    return region;
  }

  public void setRegion(String region) {
    this.region = region;
  }

  public String getBucket() {
    return bucket;
  }

  public void setBucket(String bucket) {
    this.bucket = bucket;
  }

  public String getAccessKey() {
    return accessKey;
  }

  public void setAccessKey(String accessKey) {
    this.accessKey = accessKey;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public void setSecretKey(String secretKey) {
    this.secretKey = secretKey;
  }

  public boolean isPathStyleAccess() {
    return pathStyleAccess;
  }

  public void setPathStyleAccess(boolean pathStyleAccess) {
    this.pathStyleAccess = pathStyleAccess;
  }

  public long getMaxUploadBytes() {
    return maxUploadBytes;
  }

  public void setMaxUploadBytes(long maxUploadBytes) {
    this.maxUploadBytes = maxUploadBytes;
  }

  public Duration getUploadUrlTtl() {
    return uploadUrlTtl;
  }

  public void setUploadUrlTtl(Duration uploadUrlTtl) {
    this.uploadUrlTtl = uploadUrlTtl;
  }

  public Duration getDownloadUrlTtl() {
    return downloadUrlTtl;
  }

  public void setDownloadUrlTtl(Duration downloadUrlTtl) {
    this.downloadUrlTtl = downloadUrlTtl;
  }

  public List<String> getAllowedContentTypes() {
    return allowedContentTypes;
  }

  public void setAllowedContentTypes(List<String> allowedContentTypes) {
    this.allowedContentTypes = List.copyOf(allowedContentTypes);
  }

  public int getSniffBytes() {
    return sniffBytes;
  }

  public void setSniffBytes(int sniffBytes) {
    this.sniffBytes = sniffBytes;
  }

  public Av getAv() {
    return av;
  }

  public Sweeper getSweeper() {
    return sweeper;
  }

  /**
   * ClamAV. Off by default so local development and the test suite do not need the image and its
   * signature-database startup; {@code AvStartupValidator} refuses to boot the prod profile with it
   * off, so production cannot inherit the convenient default by accident.
   */
  public static class Av {

    private boolean enabled = false;

    private String host = "localhost";

    private int port = 3310;

    private Duration timeout = Duration.ofSeconds(30);

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getHost() {
      return host;
    }

    public void setHost(String host) {
      this.host = host;
    }

    public int getPort() {
      return port;
    }

    public void setPort(int port) {
      this.port = port;
    }

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }
  }

  /** Retention and orphan cleanup. */
  public static class Sweeper {

    private boolean enabled = true;

    private String cron = "0 15 3 * * *";

    /**
     * A client that was handed an upload URL and never completed leaves a row, and possibly an
     * object, that nothing will ever reference again.
     */
    private Duration abandonedAfter = Duration.ofHours(24);

    /** Age at which a stored attachment is deleted outright. */
    private Duration retention = Duration.ofDays(365);

    private int batchSize = 200;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getCron() {
      return cron;
    }

    public void setCron(String cron) {
      this.cron = cron;
    }

    public Duration getAbandonedAfter() {
      return abandonedAfter;
    }

    public void setAbandonedAfter(Duration abandonedAfter) {
      this.abandonedAfter = abandonedAfter;
    }

    public Duration getRetention() {
      return retention;
    }

    public void setRetention(Duration retention) {
      this.retention = retention;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }
  }
}

