package com.schoolbridge.api.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link RateLimitInterceptor} across the API.
 *
 * <p>Actuator is excluded because infrastructure polls health and metrics on a fixed schedule â€”
 * throttling those turns a traffic spike into a false "service is down" signal and can trigger a
 * pointless failover. The WhatsApp webhook is excluded because Meta's retry rate is not ours to cap
 * and every request is already authenticated by HMAC signature.
 */
@Configuration
@ConditionalOnProperty(
    name = "schoolbridge.rate-limit.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RateLimitWebConfig implements WebMvcConfigurer {

  private final StringRedisTemplate redis;
  private final int authenticatedPerMinute;
  private final int anonymousPerMinute;

  public RateLimitWebConfig(
      StringRedisTemplate redis,
      @Value("${schoolbridge.rate-limit.authenticated-per-minute:120}") int authenticatedPerMinute,
      @Value("${schoolbridge.rate-limit.anonymous-per-minute:20}") int anonymousPerMinute) {
    this.redis = redis;
    this.authenticatedPerMinute = authenticatedPerMinute;
    this.anonymousPerMinute = anonymousPerMinute;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new RateLimitInterceptor(redis, authenticatedPerMinute, anonymousPerMinute))
        .addPathPatterns("/**")
        .excludePathPatterns(
            "/actuator/**",
            "/integrations/whatsapp/webhook",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/error");
  }
}
