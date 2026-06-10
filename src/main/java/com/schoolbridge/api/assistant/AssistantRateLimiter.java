package com.schoolbridge.api.assistant;

import com.schoolbridge.api.assistant.llm.AssistantProperties;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-user fixed-window rate limit on assistant requests (mirrors {@code LoginRateLimiter}). Fails
 * open if Redis is unavailable — availability of the assistant is preferred over strict limiting.
 */
@Component
public class AssistantRateLimiter {

  private static final String PREFIX = "assistant:rate:";

  private final StringRedisTemplate redis;
  private final int maxPerMinute;

  public AssistantRateLimiter(StringRedisTemplate redis, AssistantProperties properties) {
    this.redis = redis;
    this.maxPerMinute = properties.getRateLimitPerMinute();
  }

  /** Returns true if the request is allowed; false once the per-minute budget is exhausted. */
  public boolean tryAcquire(UUID userId) {
    String key = PREFIX + userId + ":" + (System.currentTimeMillis() / 60_000);
    try {
      Long count = redis.opsForValue().increment(key);
      if (count != null && count == 1L) {
        redis.expire(key, Duration.ofMinutes(1));
      }
      return count == null || count <= maxPerMinute;
    } catch (RedisConnectionFailureException e) {
      return true;
    }
  }
}
