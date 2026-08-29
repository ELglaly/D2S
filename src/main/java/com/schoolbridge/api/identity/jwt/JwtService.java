package com.schoolbridge.api.identity.jwt;

import com.schoolbridge.api.common.error.AuthenticationException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies RS256 access tokens; mints and hashes opaque refresh tokens. If no keypair is
 * configured, an ephemeral one is generated at startup â€” tokens then stop verifying after restart,
 * which is exactly what you want in dev but never in prod.
 */
@Service
public class JwtService {

  private static final Logger log = LoggerFactory.getLogger(JwtService.class);
  private static final int REFRESH_TOKEN_BYTES = 32;
  private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

  private final RSAPrivateKey privateKey;
  private final RSAPublicKey publicKey;
  private final Duration accessTtl;
  private final String issuer;
  private final String kid;

  public JwtService(JwtProperties props) {
    KeyPair keyPair = resolveKeyPair(props);
    this.privateKey = (RSAPrivateKey) keyPair.getPrivate();
    this.publicKey = (RSAPublicKey) keyPair.getPublic();
    this.accessTtl = props.accessTtl() == null ? Duration.ofMinutes(15) : props.accessTtl();
    this.issuer = props.issuer() == null ? "schoolbridge" : props.issuer();
    this.kid = props.kid() == null ? "dev" : props.kid();
  }

  /** Builds a signed access token. Caller supplies subject + custom claims. */
  public String issueAccess(String subject, Map<String, Object> claims) {
    Instant now = Instant.now();
    return Jwts.builder()
        .header()
        .keyId(kid)
        .and()
        .issuer(issuer)
        .subject(subject)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(accessTtl)))
        .claims()
        .add(claims)
        .and()
        .signWith(privateKey, Jwts.SIG.RS256)
        .compact();
  }

  /**
   * Parses, verifies signature + issuer + expiry. Throws {@link AuthenticationException} on any
   * failure.
   */
  public Claims parse(String token) {
    try {
      return Jwts.parser()
          .verifyWith(publicKey)
          .requireIssuer(issuer)
          .build()
          .parseSignedClaims(token)
          .getPayload();
    } catch (JwtException | IllegalArgumentException ex) {
      throw new AuthenticationException("error.auth.token_invalid");
    }
  }

  /** Returns a fresh base64url opaque refresh token. */
  public String newRefreshToken() {
    byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** SHA-256 hex of a refresh token â€” what we actually store. */
  public String hashRefresh(String token) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public Duration accessTtl() {
    return accessTtl;
  }

  private static KeyPair resolveKeyPair(JwtProperties props) {
    if (hasText(props.privateKey()) && hasText(props.publicKey())) {
      return new KeyPair(parsePublicKey(props.publicKey()), parsePrivateKey(props.privateKey()));
    }
    log.warn(
        "schoolbridge.jwt.{private-key,public-key} not configured; generating an ephemeral RSA"
            + " keypair (DEV ONLY â€” tokens stop verifying after restart).");
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      return gen.generateKeyPair();
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to generate JWT keypair", ex);
    }
  }

  private static boolean hasText(String s) {
    return s != null && !s.isBlank();
  }

  private static PrivateKey parsePrivateKey(String pem) {
    try {
      byte[] der = Base64.getDecoder().decode(stripPem(pem));
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Invalid schoolbridge.jwt.private-key (expected PKCS#8 PEM)", ex);
    }
  }

  private static PublicKey parsePublicKey(String pem) {
    try {
      byte[] der = Base64.getDecoder().decode(stripPem(pem));
      return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Invalid schoolbridge.jwt.public-key (expected X.509 SubjectPublicKeyInfo PEM)", ex);
    }
  }

  private static String stripPem(String pem) {
    return pem.replaceAll("-----BEGIN [^-]+-----", "")
        .replaceAll("-----END [^-]+-----", "")
        .replaceAll("\\s", "");
  }
}

