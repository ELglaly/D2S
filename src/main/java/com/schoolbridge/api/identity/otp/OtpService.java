package com.schoolbridge.api.identity.otp;

import com.schoolbridge.api.common.error.AuthenticationException;
import com.schoolbridge.api.common.error.RateLimitException;
import groovy.util.logging.Slf4j;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Stores OTP tickets and parent session tokens in Redis. Tickets are stringly serialized as {@code
 * userId|schoolId|codeHash|attempts}; tokens as {@code userId|schoolId}. Constants chosen per the
 * SRS: 6-digit OTP, 5-minute TTL, 5 attempts, parent token 24h sliding TTL.
 */
@lombok.extern.slf4j.Slf4j
@Service
@Slf4j
public class OtpService {

  private static final String TICKET_PREFIX = "otp:ticket:";
  private static final String TOKEN_PREFIX = "parent:token:";
  private static final Duration OTP_TTL = Duration.ofMinutes(5);
  private static final Duration TOKEN_TTL = Duration.ofHours(24);
  private static final int MAX_ATTEMPTS = 5;

  private final StringRedisTemplate redis;
  private final SecureRandom random = new SecureRandom();

  public OtpService(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** Stores a hashed OTP keyed by a fresh ticketId. Returns (ticketId, rawCode). */
  public IssuedOtp issue(UUID userId, UUID schoolId) {
    String code = String.format("%06d", random.nextInt(1_000_000));
    String ticketId = UUID.randomUUID().toString();
    String value = userId + "|" + schoolId + "|" + sha256(code) + "|0";
    redis.opsForValue().set(TICKET_PREFIX + ticketId, value, OTP_TTL);
    log.info("code: {}", code);
    return new IssuedOtp(ticketId, code);
  }

  /**
   * Constant-time verifies the supplied code against the stored hash; on success, mints and stores
   * a parent session token and returns it. On any failure throws {@link AuthenticationException}
   * with the appropriate i18n key, or {@link RateLimitException} once the attempt cap is hit.
   */
  public ParentSession verify(String ticketId, String code) {
    String stored = redis.opsForValue().get(TICKET_PREFIX + ticketId);
    if (stored == null) {
      throw new AuthenticationException("error.otp.expired");
    }
    String[] parts = stored.split("\\|");
    UUID userId = UUID.fromString(parts[0]);
    UUID schoolId = UUID.fromString(parts[1]);
    String storedHash = parts[2];
    int attempts = Integer.parseInt(parts[3]) + 1;

    if (!constantTimeEquals(storedHash, sha256(code))) {
      if (attempts >= MAX_ATTEMPTS) {
        redis.delete(TICKET_PREFIX + ticketId);
        throw new RateLimitException("error.otp.too_many_attempts");
      }
      String newValue = parts[0] + "|" + parts[1] + "|" + storedHash + "|" + attempts;
      redis.opsForValue().set(TICKET_PREFIX + ticketId, newValue, OTP_TTL);
      throw new AuthenticationException("error.otp.invalid");
    }

    redis.delete(TICKET_PREFIX + ticketId);
    byte[] tokenBytes = new byte[32];
    random.nextBytes(tokenBytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    redis.opsForValue().set(TOKEN_PREFIX + sha256(token), userId + "|" + schoolId, TOKEN_TTL);
    return new ParentSession(token, userId, schoolId);
  }

  /** Resolves a parent session by token. Refreshes its sliding TTL. Returns null if unknown. */
  public ParentSession resolve(String token) {
    String key = TOKEN_PREFIX + sha256(token);
    String stored = redis.opsForValue().get(key);
    if (stored == null) {
      return null;
    }
    String[] parts = stored.split("\\|");
    redis.expire(key, TOKEN_TTL);
    return new ParentSession(token, UUID.fromString(parts[0]), UUID.fromString(parts[1]));
  }

  public void revoke(String token) {
    redis.delete(TOKEN_PREFIX + sha256(token));
  }

  static String sha256(String value) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null || a.length() != b.length()) {
      return false;
    }
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  public record IssuedOtp(String ticketId, String code) {}

  public record ParentSession(String token, UUID userId, UUID schoolId) {}
}

