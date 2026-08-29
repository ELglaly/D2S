package com.schoolbridge.api.attachments.av;

import com.schoolbridge.api.attachments.StorageProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to boot the production profile with AV scanning switched off.
 *
 * <p>Same shape, and the same reasoning, as {@link
 * com.schoolbridge.api.common.tenancy.RlsStartupValidator}: the convenient default exists so local
 * development and the test suite do not need a ClamAV image, and a convenient default is exactly
 * the kind of thing a production deployment inherits by omission. Without this check the failure
 * mode is silent â€” uploads succeed, downloads work, and every attachment is recorded {@code
 * SKIPPED} while everyone believes files are being scanned.
 */
@Component
@Profile("prod")
public class AvStartupValidator {

  private static final Logger log = LoggerFactory.getLogger(AvStartupValidator.class);

  private final StorageProperties properties;

  public AvStartupValidator(StorageProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void validate() {
    if (!properties.getAv().isEnabled()) {
      throw new IllegalStateException(
          "Anti-virus scanning is disabled (schoolbridge.storage.av.enabled=false) under the prod"
              + " profile. Attachments would be stored and served back to parents without ever"
              + " being scanned, and recorded as SKIPPED rather than CLEAN. Set STORAGE_AV_ENABLED"
              + " and point STORAGE_AV_HOST at a clamd instance â€” see docs/RUNBOOK.md,"
              + " 'Attachment storage'.");
    }
    log.info(
        "av_enabled=true host={} port={}",
        properties.getAv().getHost(),
        properties.getAv().getPort());
  }
}

