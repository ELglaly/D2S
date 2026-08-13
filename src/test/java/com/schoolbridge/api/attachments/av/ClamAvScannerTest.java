package com.schoolbridge.api.attachments.av;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schoolbridge.api.attachments.AvResult;
import com.schoolbridge.api.attachments.StorageProperties;
import com.schoolbridge.api.common.error.IntegrationException;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the clamd {@code INSTREAM} framing against a fake daemon.
 *
 * <p>A real ClamAV container is deliberately not used here: the image is ~250 MB and spends a
 * minute loading its signature database, which would be paid on every {@code mvn verify}. What can
 * actually regress in our code is the wire framing and the response parsing, and both are exercised
 * below.
 */
class ClamAvScannerTest {

  private FakeClamd clamd;

  @AfterEach
  void tearDown() throws IOException {
    if (clamd != null) {
      clamd.close();
    }
  }

  @Test
  void reportsCleanAndForwardsTheWholeBodyInLengthPrefixedChunks() throws Exception {
    byte[] payload = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
    clamd = FakeClamd.replying("stream: OK\0");

    AvScanner.ScanOutcome outcome = scannerFor(clamd).scan(new ByteArrayInputStream(payload));

    assertThat(outcome.result()).isEqualTo(AvResult.CLEAN);
    assertThat(clamd.command()).isEqualTo("zINSTREAM\0");
    assertThat(clamd.body()).isEqualTo(payload);
  }

  @Test
  void reportsInfectedAndExtractsTheSignatureName() throws Exception {
    clamd = FakeClamd.replying("stream: Eicar-Test-Signature FOUND\0");

    AvScanner.ScanOutcome outcome =
        scannerFor(clamd).scan(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));

    assertThat(outcome.isInfected()).isTrue();
    assertThat(outcome.signature()).isEqualTo("Eicar-Test-Signature");
  }

  @Test
  void throwsRatherThanAssumingCleanWhenTheDaemonErrors() throws Exception {
    clamd = FakeClamd.replying("INSTREAM size limit exceeded. ERROR\0");

    assertThatThrownBy(
            () ->
                scannerFor(clamd)
                    .scan(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IntegrationException.class);
  }

  @Test
  void throwsWhenTheDaemonIsUnreachable() throws Exception {
    // Bind and immediately release, so the port is almost certainly closed.
    int deadPort;
    try (ServerSocket probe = new ServerSocket(0)) {
      deadPort = probe.getLocalPort();
    }
    // An unscanned object must not reach CLEAN; failing the upload is the correct direction.
    assertThatThrownBy(
            () ->
                scanner("localhost", deadPort)
                    .scan(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IntegrationException.class);
  }

  @Test
  void sendsATerminatingZeroLengthChunkForAnEmptyBody() throws Exception {
    clamd = FakeClamd.replying("stream: OK\0");

    scannerFor(clamd).scan(new ByteArrayInputStream(new byte[0]));

    assertThat(clamd.body()).isEmpty();
    assertThat(clamd.sawTerminator()).isTrue();
  }

  private static ClamAvScanner scannerFor(FakeClamd clamd) {
    return scanner("localhost", clamd.port());
  }

  private static ClamAvScanner scanner(String host, int port) {
    StorageProperties properties = new StorageProperties();
    properties.getAv().setHost(host);
    properties.getAv().setPort(port);
    properties.getAv().setTimeout(Duration.ofSeconds(5));
    return new ClamAvScanner(properties);
  }

  /** Accepts one connection, decodes the INSTREAM framing, and replies with a canned response. */
  private static final class FakeClamd implements AutoCloseable {

    private final ServerSocket server;
    private final Thread thread;
    private final AtomicReference<String> command = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>(new byte[0]);
    private final AtomicReference<Boolean> terminator = new AtomicReference<>(false);

    private FakeClamd(String reply) throws IOException {
      this.server = new ServerSocket(0);
      this.thread =
          new Thread(
              () -> {
                try (Socket socket = server.accept();
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    OutputStream out = socket.getOutputStream()) {
                  command.set(readCommand(in));
                  body.set(readChunks(in));
                  terminator.set(true);
                  out.write(reply.getBytes(StandardCharsets.US_ASCII));
                  out.flush();
                } catch (IOException ignored) {
                  // Test socket teardown; nothing to assert here.
                }
              });
      this.thread.setDaemon(true);
      this.thread.start();
    }

    static FakeClamd replying(String reply) throws IOException {
      return new FakeClamd(reply);
    }

    private static String readCommand(InputStream in) throws IOException {
      StringBuilder builder = new StringBuilder();
      int b;
      while ((b = in.read()) != -1) {
        builder.append((char) b);
        if (b == 0) {
          break;
        }
      }
      return builder.toString();
    }

    private static byte[] readChunks(DataInputStream in) throws IOException {
      java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
      while (true) {
        int length = in.readInt();
        if (length == 0) {
          return collected.toByteArray();
        }
        byte[] chunk = new byte[length];
        in.readFully(chunk);
        collected.write(chunk);
      }
    }

    int port() {
      return server.getLocalPort();
    }

    String command() throws InterruptedException {
      thread.join(5_000);
      return command.get();
    }

    byte[] body() throws InterruptedException {
      thread.join(5_000);
      return body.get();
    }

    boolean sawTerminator() throws InterruptedException {
      thread.join(5_000);
      return terminator.get();
    }

    @Override
    public void close() throws IOException {
      server.close();
    }
  }
}
