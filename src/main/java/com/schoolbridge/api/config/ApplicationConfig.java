package com.schoolbridge.api.config;

import com.schoolbridge.api.attachments.StorageProperties;
import com.schoolbridge.api.common.crypto.CryptoProperties;
import com.schoolbridge.api.identity.jwt.JwtProperties;
import com.schoolbridge.api.integrations.push.PushProperties;
import com.schoolbridge.api.integrations.whatsapp.WhatsAppProperties;
import org.modelmapper.ModelMapper;
import org.modelmapper.config.Configuration.AccessLevel;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Wires shared beans and enables scheduling + typed configuration properties.
 *
 * <p>The explicit {@code @EnableTransactionManagement(order = ...)} pins Spring's transaction
 * interceptor to a precedence higher than {@link
 * com.schoolbridge.api.common.tenancy.TenantFilterAspect} so the tx is already open when the aspect
 * enables the Hibernate tenant filter on the bound session.
 */
@Configuration
@EnableScheduling
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 10)
@EnableConfigurationProperties({
  CryptoProperties.class,
  JwtProperties.class,
  PushProperties.class,
  StorageProperties.class,
  WhatsAppProperties.class
})
public class ApplicationConfig {

  @Bean
  public ModelMapper modelMapper() {
    ModelMapper mapper = new ModelMapper();
    mapper
        .getConfiguration()
        .setMatchingStrategy(MatchingStrategies.STRICT)
        .setFieldMatchingEnabled(true)
        .setFieldAccessLevel(AccessLevel.PRIVATE)
        .setSkipNullEnabled(true);
    return mapper;
  }
}

