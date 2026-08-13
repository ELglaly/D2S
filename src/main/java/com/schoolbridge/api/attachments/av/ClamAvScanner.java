package com.schoolbridge.api.attachments.av;

import com.schoolbridge.api.attachments.StorageProperties;
import com.schoolbridge.api.common.error.IntegrationException;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans an object by streaming it to a clamd daemon over the {@code INSTREAM} protocol.
 *
 * <p>Wire format, from clamd(8):
 *
 * <pre>
 *   -&gt; zINSTREAM\0
 *   -&gt; &lt;4-byte big-endian length&gt;&lt;that many bytes&gt;   (repeated)
 *   -&gt; &lt;4-byte zero&gt;                                  (end of stream)
 *   &lt;- "stream: OK\0"  |  "stream: &lt;Name&gt; FOUND\0"  |  "... ERROR\0"
 * </pre>
 *
 * <p>The body is forwarded chunk by chunk and never held whole in memory — at the configured upload
 * cap, buffering per concurrent upload is how a scanner turns into an availability problem.
 *
 * <p>A scanner that cannot be reached throws rather than returning clean. Failing the upload is the
 * correct direction: an attachment that is stored but unscanned would still be downloadable, which
 * is precisely the state this pipeline exists to prevent.
 */
public class ClamAvScanner implements AvScanner {

  private static final Logger log = LoggerFactory.getLogger(ClamAvScanner.class);

  /** Comfortably under clamd's default StreamMaxLength, and small enough to keep heap flat. */
  private static final int CHUNK_BYTES = 32 * 1024;

  private static final byte[] INSTREAM = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);

  private final String host;
  private final int port;
  private final int timeoutMillis;

  public ClamAvScanner(StorageProperties properties) {
    this.host = properties.getAv().getHost();
    this.port = properties.getAv().getPort();
    this.timeoutMillis = (int) properties.getAv().getTimeout().toMillis();
  }

  @Override
  public ScanOutcome scan(InputStream content) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), timeoutMillis);
      socket.setSoTimeout(timeoutMillis);

      try (DataOutputStream out =
              new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
          InputStream in = socket.getInputStream()) {
        out.write(INSTREAM);
        out.flush();
        streamBody(content, out);
        return interpret(readResponse(in));
      }
    } catch (IOException e) {
      // Deliberately not "assume clean": an unscanned object that reaches CLEAN is downloadable.
      throw new IntegrationException("error.attachment.av_unavailable", e);
    }
  }

  private static void streamBody(InputStream content, DataOutputStream out) throws IOException {
    byte[] chunk = new byte[CHUNK_BYTES];
    int read;
    while ((read = content.read(chunk)) != -1) {
      if (read > 0) {
        out.writeInt(read);
        out.write(chunk, 0, read);
      }
    }
    out.writeInt(0);
    out.flush();
  }

  private static String readResponse(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int b;
    // clamd terminates the reply with NUL for a "z"-prefixed command; it also closes the socket,
    // so -1 ends the read for daemons that answer without the terminator.
    while ((b = in.read()) != -1 && b != 0) {
      buffer.write(b);
    }
    return buffer.toString(StandardCharsets.US_ASCII).trim();
  }

  private static ScanOutcome interpret(String response) {
    if (response.endsWith("OK")) {
      return ScanOutcome.clean();
    }
    if (response.endsWith("FOUND")) {
      // "stream: Eicar-Test-Signature FOUND" -> "Eicar-Test-Signature"
      int start = response.indexOf(": ");
      String signature =
          start >= 0
              ? response.substring(start + 2, response.length() - " FOUND".length()).trim()
              : response;
      log.warn("ClamAV reported a signature match: {}", signature);
      return ScanOutcome.infected(signature);
    }
    log.error("Unexpected clamd response: {}", response);
    throw new IntegrationException("error.attachment.av_unavailable");
  }
}
