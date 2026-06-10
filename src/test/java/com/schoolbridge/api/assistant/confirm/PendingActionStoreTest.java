package com.schoolbridge.api.assistant.confirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class PendingActionStoreTest {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @Mock StringRedisTemplate redis;
  @Mock ValueOperations<String, String> ops;

  private PendingAction sample(String token) {
    return new PendingAction(
        token,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "mark_all_present",
        JSON.createObjectNode().put("classId", UUID.randomUUID().toString()),
        Map.of("studentCount", 24),
        false,
        Instant.now(),
        Instant.now().plusSeconds(300));
  }

  @Test
  void putSerializesUnderPrefixedKeyWithTtl() {
    when(redis.opsForValue()).thenReturn(ops);
    PendingAction action = sample("tok1");

    new PendingActionStore(redis, JSON).put(action, Duration.ofMinutes(5));

    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(ops).set(key.capture(), any(), eq(Duration.ofMinutes(5)));
    assertThat(key.getValue()).isEqualTo("assistant:pending:tok1");
  }

  @Test
  void consumeUsesAtomicGetAndDeleteAndDeserializes() throws Exception {
    when(redis.opsForValue()).thenReturn(ops);
    PendingAction action = sample("tok2");
    when(ops.getAndDelete("assistant:pending:tok2")).thenReturn(JSON.writeValueAsString(action));

    Optional<PendingAction> consumed = new PendingActionStore(redis, JSON).consume("tok2");

    assertThat(consumed).isPresent();
    assertThat(consumed.get().token()).isEqualTo("tok2");
    assertThat(consumed.get().toolName()).isEqualTo("mark_all_present");
    assertThat(consumed.get().userId()).isEqualTo(action.userId());
  }

  @Test
  void consumeReturnsEmptyWhenTokenAlreadyGone() {
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.getAndDelete("assistant:pending:gone")).thenReturn(null);
    assertThat(new PendingActionStore(redis, JSON).consume("gone")).isEmpty();
  }
}
