package com.schoolbridge.api.integrations.whatsapp.webhook;

import com.schoolbridge.api.integrations.whatsapp.WhatsAppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Computes HMAC-SHA256 over the raw webhook body and compares it, in constant time, to the value
 * supplied in {@code X-Hub-Signature-256}. The header arrives as {@code sha256=<hex>} per the Meta
 * Cloud webhook spec.
 *
 * <p>The verification MUST operate on the EXACT bytes Meta sent; the controller therefore consumes
 * {@code @RequestBody byte[]} (Spring's {@code ByteArrayHttpMessageConverter}) before any JSON
 * parsing happens — see {@code feedback_outbox_audit_mapof_npe} for why "operate on the wire bytes"
 * is the only safe path here.
 */
@Component
public class WebhookSignatureVerifier {

  private static final String HEADER_PREFIX = "sha256=";

  private final WhatsAppProperties properties;

  public WebhookSignatureVerifier(WhatsAppProperties properties) {
    this.properties = properties;
  }

  public boolean matches(byte[] rawBody, String headerValue) {
    if (rawBody == null || headerValue == null || !headerValue.startsWith(HEADER_PREFIX)) {
      return false;
    }
    String secret = properties.getAppSecret();
    if (secret == null || secret.isBlank()) {
      return false;
    }
    String expected = hmacSha256Hex(rawBody, secret);
    String supplied = headerValue.substring(HEADER_PREFIX.length());
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
  }

  static String hmacSha256Hex(byte[] body, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to compute HMAC-SHA256", ex);
    }
  }
}
