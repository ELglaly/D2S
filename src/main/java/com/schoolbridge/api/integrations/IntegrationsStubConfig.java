package com.schoolbridge.api.integrations;

import com.schoolbridge.api.integrations.sms.LoggingSmsClient;
import com.schoolbridge.api.integrations.sms.SmsClient;
import com.schoolbridge.api.integrations.whatsapp.LoggingWhatsAppClient;
import com.schoolbridge.api.integrations.whatsapp.WhatsAppClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Registers no-op stub beans for external integration clients when no real provider is configured.
 * Using a {@code @Configuration} class (rather than {@code @ConditionalOnMissingBean} directly on
 * {@code @Component} classes) guarantees that Spring Boot evaluates the conditions in declaration
 * order, after all other bean definitions are stable.
 */
@Configuration
@Profile("!test")
class IntegrationsStubConfig {

  @Bean
  @ConditionalOnMissingBean(SmsClient.class)
  SmsClient loggingSmsClient() {
    return new LoggingSmsClient();
  }

  @Bean
  @ConditionalOnMissingBean(WhatsAppClient.class)
  WhatsAppClient loggingWhatsAppClient() {
    return new LoggingWhatsAppClient();
  }
}
