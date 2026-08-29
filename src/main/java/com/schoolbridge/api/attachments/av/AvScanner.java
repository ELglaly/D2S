package com.schoolbridge.api.attachments.av;

import com.schoolbridge.api.attachments.AvResult;
import java.io.InputStream;

/** Anti-virus scanning of an uploaded object, server-side, before it can ever be downloaded. */
public interface AvScanner {

  /**
   * Outcome of one scan. {@code signature} is populated only for {@link AvResult#INFECTED} and is
   * the scanner's own name for the match, kept for the audit trail.
   */
  record ScanOutcome(AvResult result, String signature) {

    public static ScanOutcome clean() {
      return new ScanOutcome(AvResult.CLEAN, null);
    }

    public static ScanOutcome infected(String signature) {
      return new ScanOutcome(AvResult.INFECTED, signature);
    }

    public static ScanOutcome skipped() {
      return new ScanOutcome(AvResult.SKIPPED, null);
    }

    public boolean isInfected() {
      return result == AvResult.INFECTED;
    }
  }

  /**
   * Scans the stream. The caller owns the stream and closes it.
   *
   * <p>Implementations must stream rather than buffer: the object is up to the configured upload
   * cap, and holding that in heap per concurrent upload is how this becomes an availability
   * problem.
   */
  ScanOutcome scan(InputStream content);
}

