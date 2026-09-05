package com.schoolbridge.api.assistant.confirm;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Issues opaque, single-use confirmation tokens. A token carries no data â€” it is a 256-bit random
 * handle to a {@link PendingAction} held server-side in Redis, bound there to the issuing user and
 * consumed atomically on execute. Unguessable + server-validated + single-use is the security
 * property; the stored record (not the token) is the source of truth.
 */
@Component
public class ConfirmationTokenService {

  private static final int TOKEN_BYTES = 32;
  private final SecureRandom random = new SecureRandom();

  public String generate() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
