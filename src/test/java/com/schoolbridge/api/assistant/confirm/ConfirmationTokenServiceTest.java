package com.schoolbridge.api.assistant.confirm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfirmationTokenServiceTest {

  @Test
  void generatesUniqueNonEmptyTokens() {
    ConfirmationTokenService service = new ConfirmationTokenService();
    Set<String> tokens = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      String token = service.generate();
      assertThat(token).isNotBlank();
      assertThat(tokens.add(token)).as("token must be unique").isTrue();
    }
  }
}
