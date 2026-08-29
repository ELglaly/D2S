package com.schoolbridge.api.common.security.authz;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Spring caching and pins an explicit Caffeine {@link CacheManager} so Boot does NOT
 * auto-select the Redis cache manager (Redis is on the classpath). Authorization must keep working
 * even if Redis is unavailable, hence a local, in-process cache.
 *
 * <p>The cache holds â‰¤4 entries (one per {@link com.schoolbridge.api.identity.UserRole}). Entries
 * are evicted explicitly when an admin changes a role's permissions; the 10-minute {@code
 * expireAfterWrite} is a safety net that also bounds cross-instance staleness on multi-node
 * deployments (each JVM has its own cache).
 */
@Configuration
@EnableCaching
public class AuthzCacheConfig {

  public static final String ROLE_PERMISSIONS_CACHE = "rolePermissions";

  @Bean
  public CacheManager cacheManager() {
    CaffeineCacheManager manager = new CaffeineCacheManager(ROLE_PERMISSIONS_CACHE);
    manager.setCaffeine(
        Caffeine.newBuilder().maximumSize(64).expireAfterWrite(Duration.ofMinutes(10)));
    return manager;
  }
}

