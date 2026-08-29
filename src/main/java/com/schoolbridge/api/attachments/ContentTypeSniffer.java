package com.schoolbridge.api.attachments;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Identifies a file from its leading bytes.
 *
 * <p>The client's declared {@code Content-Type} is a string it chose; it is checked for
 * <em>agreement</em> with what this returns, never used in its place. A ".pdf" carrying a PE header
 * and declaring {@code application/pdf} is exactly the upload this exists to catch.
 *
 * <p>Only the allow-listed formats are recognised. Anything unrecognised is rejected rather than
 * passed through, so the list of things that can be stored is closed rather than open â€” a new
 * format has to be added deliberately.
 */
@Component
public class ContentTypeSniffer {

  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
  private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
  private static final byte[] WEBP = {'W', 'E', 'B', 'P'};

  /** Returns the detected media type, or empty when the bytes match no allow-listed format. */
  public Optional<String> sniff(byte[] head) {
    if (head == null || head.length < 4) {
      return Optional.empty();
    }
    if (startsWith(head, JPEG)) {
      return Optional.of("image/jpeg");
    }
    if (startsWith(head, PNG)) {
      return Optional.of("image/png");
    }
    if (startsWith(head, PDF)) {
      return Optional.of("application/pdf");
    }
    // WebP is a RIFF container: "RIFF" <4-byte size> "WEBP". The size field sits between the two
    // markers, so a plain prefix match is not enough.
    if (startsWith(head, RIFF) && head.length >= 12 && matchesAt(head, 8, WEBP)) {
      return Optional.of("image/webp");
    }
    return Optional.empty();
  }

  private static boolean startsWith(byte[] data, byte[] prefix) {
    return matchesAt(data, 0, prefix);
  }

  private static boolean matchesAt(byte[] data, int offset, byte[] expected) {
    if (data.length < offset + expected.length) {
      return false;
    }
    for (int i = 0; i < expected.length; i++) {
      if (data[offset + i] != expected[i]) {
        return false;
      }
    }
    return true;
  }
}

