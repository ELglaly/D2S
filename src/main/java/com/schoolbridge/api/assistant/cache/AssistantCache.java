package com.schoolbridge.api.assistant.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis cache for READ answers only â€” keyed by {user, normalized-question, day}. Action proposals
 * are never cached. Degrades gracefully when Redis is unavailable (miss on read, no-op on write),
 * mirroring {@code IdempotencyService}.
 */
@Component
public class AssistantCache {

  private static final Logger log = LoggerFactory.getLogger(AssistantCache.class);
  private static final String PREFIX = "assistant:answer:";

  private final StringRedisTemplate redis;

  public AssistantCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public String key(UUID userId, String question) {
    return PREFIX + userId + ":" + LocalDate.now() + ":" + sha256(normalize(question));
  }

  public Optional<String> get(String key) {
    try {
      return Optional.ofNullable(redis.opsForValue().get(key));
    } catch (RedisConnectionFailureException e) {
      log.warn("Redis unavailable â€” assistant cache miss forced");
      return Optional.empty();
    }
  }

  public void put(String key, String answer, Duration ttl) {
    try {
      redis.opsForValue().set(key, answer, ttl);
    } catch (RedisConnectionFailureException e) {
      log.warn("Redis unavailable â€” assistant answer not cached");
    }
  }

  static String normalize(String question) {
    return question.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
