package com.schoolbridge.api.common.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Produces a deterministic, keyed hash (HMAC-SHA256) of a sensitive value so that an
 * AES-GCM-encrypted column (which is non-deterministic and unsearchable) can still be looked up by
 * an equality match on a separate blind-index column — e.g. finding a user by phone number.
 */
@Component
public class BlindIndexHasher {

  private final SecretKeySpec key;

  public BlindIndexHasher(CryptoProperties properties) {
    byte[] keyBytes = Base64.getDecoder().decode(properties.blindIndexKey());
    if (keyBytes.length != 32) {
      throw new IllegalStateException(
          "schoolbridge.crypto.blind-index-key must decode to 32 bytes");
    }
    this.key = new SecretKeySpec(keyBytes, "HmacSHA256");
  }

  /** Normalizes (trim + lowercase) then returns the base64 HMAC-SHA256 of the value. */
  public String hash(String value) {
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      byte[] digest = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(digest);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to compute blind index", ex);
    }
  }
}
