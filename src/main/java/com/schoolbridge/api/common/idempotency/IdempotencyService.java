package com.schoolbridge.api.common.idempotency;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed store for idempotent request handling: an in-progress lock plus a cached response
 * keyed by the (tenant + user + method + path + Idempotency-Key) tuple, retained for {@code
 * schoolbridge.idempotency.ttl}.
 *
 * <p>All Redis operations degrade gracefully when Redis is unavailable: find() returns empty,
 * tryAcquire() returns true (optimistic pass-through), and store/release are no-ops.
 */
@Service
public class IdempotencyService {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

  private static final String LOCK_PREFIX = "idem:lock:";
  private static final String RESP_PREFIX = "idem:resp:";

  private final StringRedisTemplate redis;
  private final Duration ttl;

  public IdempotencyService(
      StringRedisTemplate redis, @Value("${schoolbridge.idempotency.ttl}") Duration ttl) {
    this.redis = redis;
    this.ttl = ttl;
  }

  /** Attempts to acquire the in-progress lock for a key; false if another request holds it. */
  public boolean tryAcquire(String key) {
    try {
      Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_PREFIX + key, "1", ttl);
      return Boolean.TRUE.equals(acquired);
    } catch (RedisConnectionFailureException e) {
      log.warn("Redis unavailable â€” idempotency lock skipped for key {}", key);
      return true;
    }
  }

  public void release(String key) {
    try {
      redis.delete(LOCK_PREFIX + key);
    } catch (RedisConnectionFailureException e) {
      log.warn("Redis unavailable â€” idempotency lock release skipped for key {}", key);
    }
  }

  public Optional<CachedResponse> find(String key) {
    try {
      String value = redis.opsForValue().get(RESP_PREFIX + key);
      return Optional.ofNullable(value).map(CachedResponse::decode);
    } catch (RedisConnectionFailureException e) {
      log.warn("Redis unavailable â€” idempotency cache miss forced for key {}", key);
      return Optional.empty();
    }
  }

  public void store(String key, int status, byte[] body) {
    try {
      redis.opsForValue().set(RESP_PREFIX + key, CachedResponse.encode(status, body), ttl);
    } catch (RedisConnectionFailureException e) {
      log.warn("Redis unavailable â€” idempotency response not cached for key {}", key);
    }
  }

  /** A cached HTTP response: status code plus body, serialized as {@code status|base64(body)}. */
  public record CachedResponse(int status, byte[] body) {

    static String encode(int status, byte[] body) {
      return status + "|" + Base64.getEncoder().encodeToString(body == null ? new byte[0] : body);
    }

    static CachedResponse decode(String value) {
      int sep = value.indexOf('|');
      int status = Integer.parseInt(value.substring(0, sep));
      byte[] body = Base64.getDecoder().decode(value.substring(sep + 1));
      return new CachedResponse(status, body);
    }
  }
}
