package com.schoolbridge.api.attachments.av;

import com.schoolbridge.api.attachments.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the AV implementation.
 *
 * <p>{@code matchIfMissing = false} means the disabled scanner is what you get unless {@code
 * schoolbridge.storage.av.enabled} is explicitly true. {@link AvStartupValidator} stops that
 * default from reaching production.
 */
@Configuration
public class AvConfig {

  @Bean
  @ConditionalOnProperty(prefix = "schoolbridge.storage.av", name = "enabled", havingValue = "true")
  public AvScanner clamAvScanner(StorageProperties properties) {
    return new ClamAvScanner(properties);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "schoolbridge.storage.av",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  public AvScanner disabledAvScanner() {
    return new DisabledAvScanner();
  }
}
