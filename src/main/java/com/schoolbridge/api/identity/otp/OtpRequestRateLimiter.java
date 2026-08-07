package com.schoolbridge.api.identity.otp;

import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Caps how often an OTP may be <b>requested</b> for one phone number.
 *
 * <p>{@code OtpService} already caps verify <i>attempts</i>, but nothing capped requests: {@code
 * POST /api/v1/parents/auth/otp} is {@code permitAll} and was unlimited, so a script could drive
 * unbounded WhatsApp template and SMS sends. That is a direct monetary cost per message, degrades
 * the WhatsApp Business account's quality rating, and can get the sending number banned — the
 * damage lands even though no account is ever compromised.
 *
 * <p>Two windows, because one is not enough: a short hourly cap stops a burst, and a daily cap
 * stops a slow drip that would otherwise sit under the hourly limit indefinitely.
 *
 * <p>Keys are the phone's blind-index hash, never the number itself — Redis should not become a
 * plaintext directory of every parent phone number in the platform.
 */
@Component
public class OtpRequestRateLimiter {

  private static final String HOURLY_PREFIX = "otp:req:h:";
  private static final String DAILY_PREFIX = "otp:req:d:";

  private final StringRedisTemplate redis;
  private final BlindIndexHasher blindIndex;
  private final int maxPerHour;
  private final int maxPerDay;

  public OtpRequestRateLimiter(
      StringRedisTemplate redis,
      BlindIndexHasher blindIndex,
      @Value("${schoolbridge.otp.max-requests-per-hour:3}") int maxPerHour,
      @Value("${schoolbridge.otp.max-requests-per-day:10}") int maxPerDay) {
    this.redis = redis;
    this.blindIndex = blindIndex;
    this.maxPerHour = maxPerHour;
    this.maxPerDay = maxPerDay;
  }

  /** True when this phone has already exhausted its hourly or daily OTP request budget. */
  public boolean isBlocked(String phone) {
    String hash = blindIndex.hash(phone);
    return exceeded(HOURLY_PREFIX + hash, maxPerHour) || exceeded(DAILY_PREFIX + hash, maxPerDay);
  }

  /**
   * Counts a dispatched OTP against both windows. Call this only when a message is actually sent —
   * counting unknown numbers too would let an attacker exhaust a real parent's budget by guessing.
   */
  public void recordRequest(String phone) {
    String hash = blindIndex.hash(phone);
    increment(HOURLY_PREFIX + hash, Duration.ofHours(1));
    increment(DAILY_PREFIX + hash, Duration.ofDays(1));
  }

  private boolean exceeded(String key, int max) {
    String value = redis.opsForValue().get(key);
    return value != null && Integer.parseInt(value) >= max;
  }

  private void increment(String key, Duration window) {
    Long count = redis.opsForValue().increment(key);
    if (count != null && count == 1L) {
      redis.expire(key, window);
    }
  }
}
