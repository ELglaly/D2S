package com.schoolbridge.api.identity.auth;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Enforces NFR-S5: max 5 failed login attempts per email per 15 minutes. Implemented as a Redis
 * counter with a sliding window TTL — once {@link #recordFailure(String)} pushes the counter past
 * {@link #MAX_ATTEMPTS}, {@link #isBlocked(String)} returns true until the TTL expires or a {@link
 * #reset(String)} (on successful login) clears it.
 */
@Component
public class LoginRateLimiter {

  static final int MAX_ATTEMPTS = 5;
  static final Duration WINDOW = Duration.ofMinutes(15);
  private static final String PREFIX = "login:fail:";

  private final StringRedisTemplate redis;

  public LoginRateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public boolean isBlocked(String email) {
    String value = redis.opsForValue().get(PREFIX + normalize(email));
    return value != null && Integer.parseInt(value) >= MAX_ATTEMPTS;
  }

  public void recordFailure(String email) {
    String key = PREFIX + normalize(email);
    Long count = redis.opsForValue().increment(key);
    if (count != null && count == 1L) {
      redis.expire(key, WINDOW);
    }
  }

  public void reset(String email) {
    redis.delete(PREFIX + normalize(email));
  }

  private static String normalize(String email) {
    return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
  }
}
