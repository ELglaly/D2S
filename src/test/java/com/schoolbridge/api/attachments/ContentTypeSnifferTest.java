package com.schoolbridge.api.attachments;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContentTypeSnifferTest {

  private final ContentTypeSniffer sniffer = new ContentTypeSniffer();

  @Test
  void detectsJpeg() {
    byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
    assertThat(sniffer.sniff(jpeg)).contains("image/jpeg");
  }

  @Test
  void detectsPng() {
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    assertThat(sniffer.sniff(png)).contains("image/png");
  }

  @Test
  void detectsPdf() {
    assertThat(sniffer.sniff("%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII)))
        .contains("application/pdf");
  }

  @Test
  void detectsWebpOnlyWhenTheRiffContainerActuallySaysWebp() {
    byte[] webp = riffContainer("WEBP");
    byte[] wave = riffContainer("WAVE");

    assertThat(sniffer.sniff(webp)).contains("image/webp");
    // The RIFF prefix alone must not be enough — WAV is also RIFF, and treating the prefix as the
    // signature would let any RIFF-based format in under the WebP allow-list entry.
    assertThat(sniffer.sniff(wave)).isEmpty();
  }

  @Test
  void rejectsAWindowsExecutable() {
    byte[] pe = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00};
    assertThat(sniffer.sniff(pe)).isEmpty();
  }

  @Test
  void rejectsAZipArchive() {
    // Relevant because Office documents and JARs are zips: unrecognised means rejected, so a new
    // container format cannot slip in without being added deliberately.
    byte[] zip = {'P', 'K', 0x03, 0x04, 0x14, 0x00};
    assertThat(sniffer.sniff(zip)).isEmpty();
  }

  @Test
  void rejectsSvgBecauseItIsScriptableXmlRatherThanAnImageSignature() {
    assertThat(
            sniffer.sniff(
                "<svg xmlns=\"http://www.w3.org/2000/svg\">".getBytes(StandardCharsets.UTF_8)))
        .isEmpty();
  }

  @Test
  void returnsEmptyForNullOrTooShortInput() {
    assertThat(sniffer.sniff(null)).isEqualTo(Optional.empty());
    assertThat(sniffer.sniff(new byte[] {(byte) 0xFF})).isEmpty();
    assertThat(sniffer.sniff(new byte[0])).isEmpty();
  }

  /** "RIFF" + 4-byte size + the supplied 4-character form type. */
  private static byte[] riffContainer(String formType) {
    byte[] data = new byte[16];
    System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, data, 0, 4);
    data[4] = 0x08;
    System.arraycopy(formType.getBytes(StandardCharsets.US_ASCII), 0, data, 8, 4);
    return data;
  }
}
