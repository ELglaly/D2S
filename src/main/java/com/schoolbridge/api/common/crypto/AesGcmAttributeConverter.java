package com.schoolbridge.api.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts string columns at rest with AES-256-GCM. Stored value is base64({@code IV || ciphertext
 * || tag}). Applied per field via {@code @Convert}, not auto-applied, so only sensitive columns are
 * encrypted. Hibernate resolves this Spring bean through the managed-bean container.
 */
@Component
@Converter
public class AesGcmAttributeConverter implements AttributeConverter<String, String> {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public AesGcmAttributeConverter(CryptoProperties properties) {
    byte[] keyBytes = Base64.getDecoder().decode(properties.aesKey());
    if (keyBytes.length != 32) {
      throw new IllegalStateException("schoolbridge.crypto.aes-key must decode to 32 bytes");
    }
    this.key = new SecretKeySpec(keyBytes, "AES");
  }

  @Override
  public String convertToDatabaseColumn(String attribute) {
    if (attribute == null) {
      return null;
    }
    try {
      byte[] iv = new byte[IV_LENGTH];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] ciphertext =
          cipher.doFinal(attribute.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] out =
          ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
      return Base64.getEncoder().encodeToString(out);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to encrypt column value", ex);
    }
  }

  @Override
  public String convertToEntityAttribute(String dbData) {
    if (dbData == null) {
      return null;
    }
    try {
      byte[] decoded = Base64.getDecoder().decode(dbData);
      ByteBuffer buffer = ByteBuffer.wrap(decoded);
      byte[] iv = new byte[IV_LENGTH];
      buffer.get(iv);
      byte[] ciphertext = new byte[buffer.remaining()];
      buffer.get(ciphertext);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to decrypt column value", ex);
    }
  }
}
