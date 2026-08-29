package com.schoolbridge.api.attachments.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * The object store behind the attachment pipeline. Everything the API does with user bytes goes
 * through here, and the interface exists so the S3 wire details do not leak into the service.
 *
 * <p>Note what is <em>not</em> here: no "upload" and no "download". The API never carries user
 * bytes in either direction â€” it mints presigned URLs and the client talks to storage directly. The
 * two methods that do read bytes ({@link #readHead} and {@link #openStream}) exist for inspection
 * and AV scanning, both server-side, and {@link #readHead} is bounded.
 */
public interface ObjectStorage {

  /** What the store says about an object that is already there. */
  record StoredObject(long sizeBytes, String contentType) {}

  /** A presigned request the client must reproduce exactly, headers included. */
  record PresignedUpload(String url, java.util.Map<String, String> requiredHeaders) {}

  /**
   * Presigns a PUT for exactly {@code contentLength} bytes of {@code contentType} at {@code key}.
   *
   * <p>The length is signed rather than merely validated because a presigned PUT cannot carry a
   * {@code content-length-range} condition â€” that is a presigned POST form-policy feature. Signing
   * the exact value makes the object store, not client good behaviour, the thing that rejects an
   * oversized body.
   */
  PresignedUpload presignPut(String key, String contentType, long contentLength, Duration ttl);

  /**
   * Presigns a GET, forcing a download disposition and the sniffed content type so the browser does
   * not re-sniff a file into something executable.
   */
  String presignGet(String key, String fileName, String contentType, Duration ttl);

  /** Metadata for an object, or empty when it does not exist. */
  Optional<StoredObject> head(String key);

  /** Reads at most {@code maxBytes} from the start of the object, for magic-byte sniffing. */
  byte[] readHead(String key, int maxBytes);

  /** Streams the whole object. Callers must close it. Used by the AV scanner only. */
  InputStream openStream(String key);

  /** Deletes the object. Succeeds silently when it is already gone. */
  void delete(String key);
}

