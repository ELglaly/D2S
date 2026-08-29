package com.schoolbridge.api.attachments.av;

import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records {@link com.schoolbridge.api.attachments.AvResult#SKIPPED} without looking at the bytes.
 *
 * <p>The default, so local development and {@code mvn verify} do not need a ~250 MB ClamAV image
 * and its signature-database startup on every run.
 *
 * <p>{@code SKIPPED} is deliberately not {@code CLEAN}: "nobody looked" and "someone looked and it
 * was fine" must stay distinguishable in the record, or a later audit cannot tell which attachments
 * were ever actually scanned. {@link AvStartupValidator} stops the prod profile from ever selecting
 * this implementation.
 */
public class DisabledAvScanner implements AvScanner {

  private static final Logger log = LoggerFactory.getLogger(DisabledAvScanner.class);

  @Override
  public ScanOutcome scan(InputStream content) {
    log.debug("AV scanning is disabled; recording SKIPPED without inspecting the object");
    return ScanOutcome.skipped();
  }
}

