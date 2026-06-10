package com.schoolbridge.api.assistant.confirm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed store for pending actions. {@link #consume(String)} uses an atomic GETDEL so a
 * double-tapped confirm or a replay can never run the action twice — exactly one caller wins the
 * delete, the rest see an empty result. Entries also expire on their own TTL.
 */
@Component
public class PendingActionStore {

  private static final Logger log = LoggerFactory.getLogger(PendingActionStore.class);
  private static final String PREFIX = "assistant:pending:";

  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;

  public PendingActionStore(StringRedisTemplate redis, ObjectMapper mapper) {
    this.redis = redis;
    this.mapper = mapper;
  }

  public void put(PendingAction action, Duration ttl) {
    try {
      redis.opsForValue().set(PREFIX + action.token(), serialize(action), ttl);
    } catch (RedisConnectionFailureException e) {
      log.warn("Redis unavailable — pending action not stored");
    }
  }

  /** Returns the action without consuming it (used to route a confirm to its tool). */
  public Optional<PendingAction> peek(String token) {
    try {
      return Optional.ofNullable(redis.opsForValue().get(PREFIX + token)).map(this::deserialize);
    } catch (RedisConnectionFailureException e) {
      log.warn("Redis unavailable — pending action lookup failed");
      return Optional.empty();
    }
  }

  /** Atomically fetches and removes the action; single-use. */
  public Optional<PendingAction> consume(String token) {
    try {
      return Optional.ofNullable(redis.opsForValue().getAndDelete(PREFIX + token))
          .map(this::deserialize);
    } catch (RedisConnectionFailureException e) {
      log.warn("Redis unavailable — pending action consume failed");
      return Optional.empty();
    }
  }

  private String serialize(PendingAction action) {
    try {
      return mapper.writeValueAsString(action);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize pending action", e);
    }
  }

  private PendingAction deserialize(String value) {
    try {
      return mapper.readValue(value, PendingAction.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize pending action", e);
    }
  }
}
