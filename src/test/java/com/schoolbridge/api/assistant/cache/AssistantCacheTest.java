package com.schoolbridge.api.assistant.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class AssistantCacheTest {

  @Mock StringRedisTemplate redis;
  @Mock ValueOperations<String, String> ops;

  @Test
  void keyIgnoresCaseAndWhitespace() {
    AssistantCache cache = new AssistantCache(redis);
    UUID user = UUID.randomUUID();
    assertThat(cache.key(user, "How   MANY  classes"))
        .isEqualTo(cache.key(user, "how many classes"));
  }

  @Test
  void keyDiffersByUserAndQuestion() {
    AssistantCache cache = new AssistantCache(redis);
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertThat(cache.key(a, "q")).isNotEqualTo(cache.key(b, "q"));
    assertThat(cache.key(a, "q1")).isNotEqualTo(cache.key(a, "q2"));
  }

  @Test
  void getReturnsValueFromRedis() {
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get("k")).thenReturn("answer");
    assertThat(new AssistantCache(redis).get("k")).contains("answer");
  }

  @Test
  void getDegradesGracefullyWhenRedisDown() {
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get("k")).thenThrow(new RedisConnectionFailureException("down"));
    assertThat(new AssistantCache(redis).get("k")).isEmpty();
  }
}
