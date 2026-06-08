package com.schoolbridge.api.identity.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schoolbridge.api.common.error.AuthenticationException;
import io.jsonwebtoken.Claims;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

  private JwtService newService(Duration accessTtl) {
    return new JwtService(
        new JwtProperties(null, null, accessTtl, Duration.ofDays(30), "schoolbridge", "test"));
  }

  @Test
  void signedTokenRoundTripsClaims() {
    JwtService service = newService(Duration.ofMinutes(15));
    String sub = UUID.randomUUID().toString();
    String jwt =
        service.issueAccess(sub, Map.of("kind", "USER", "role", "TEACHER", "schoolId", "school-1"));

    Claims claims = service.parse(jwt);
    assertThat(claims.getSubject()).isEqualTo(sub);
    assertThat(claims.get("kind", String.class)).isEqualTo("USER");
    assertThat(claims.get("role", String.class)).isEqualTo("TEACHER");
  }

  @Test
  void tamperedTokenIsRejected() {
    JwtService service = newService(Duration.ofMinutes(15));
    String jwt = service.issueAccess("sub", Map.of("role", "X"));
    String tampered = jwt.substring(0, jwt.length() - 4) + "AAAA";

    assertThatThrownBy(() -> service.parse(tampered))
        .isInstanceOf(AuthenticationException.class)
        .hasMessageContaining("error.auth.token_invalid");
  }

  @Test
  void expiredTokenIsRejected() throws InterruptedException {
    // Negative TTL → already expired the moment it's issued.
    JwtService service = newService(Duration.ofMillis(-1000));
    String jwt = service.issueAccess("sub", Map.of("role", "X"));

    assertThatThrownBy(() -> service.parse(jwt))
        .isInstanceOf(AuthenticationException.class)
        .hasMessageContaining("error.auth.token_invalid");
  }

  @Test
  void crossKeypairTokensDoNotVerify() {
    JwtService a = newService(Duration.ofMinutes(15));
    JwtService b = newService(Duration.ofMinutes(15));
    String jwt = a.issueAccess("sub", Map.of("role", "X"));

    assertThatThrownBy(() -> b.parse(jwt)).isInstanceOf(AuthenticationException.class);
  }

  @Test
  void refreshTokenHashIsStableForSameInput() {
    JwtService service = newService(Duration.ofMinutes(15));
    String token = service.newRefreshToken();
    assertThat(service.hashRefresh(token)).isEqualTo(service.hashRefresh(token));
    assertThat(service.newRefreshToken()).isNotEqualTo(token);
  }
}
