package com.schoolbridge.api.attachments.storage;

import com.schoolbridge.api.attachments.StorageProperties;
import com.schoolbridge.api.common.error.IntegrationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3-compatible {@link ObjectStorage}: real S3, Cloudflare R2, or MinIO behind an endpoint
 * override.
 */
@Component
public class S3ObjectStorage implements ObjectStorage {

  private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

  private final S3Client s3;
  private final S3Presigner presigner;
  private final String bucket;

  public S3ObjectStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
    this.s3 = s3;
    this.presigner = presigner;
    this.bucket = properties.getBucket();
  }

  @Override
  public PresignedUpload presignPut(
      String key, String contentType, long contentLength, Duration ttl) {
    PutObjectRequest put =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(contentLength)
            .build();

    PresignedPutObjectRequest presigned =
        presigner.presignPutObject(
            PutObjectPresignRequest.builder().signatureDuration(ttl).putObjectRequest(put).build());

    // Whatever the signer decided to cover, the client has to reproduce byte for byte or storage
    // rejects the PUT. Handing the list back is the difference between an API the client can use
    // and one where a 403 from S3 is the first hint that a header was missing.
    Map<String, String> required = new LinkedHashMap<>();
    presigned
        .signedHeaders()
        .forEach(
            (name, values) -> {
              if (!values.isEmpty()) {
                required.put(name, values.get(0));
              }
            });
    required.putIfAbsent("Content-Type", contentType);
    required.putIfAbsent("Content-Length", Long.toString(contentLength));

    return new PresignedUpload(presigned.url().toString(), Map.copyOf(required));
  }

  @Override
  public String presignGet(String key, String fileName, String contentType, Duration ttl) {
    GetObjectRequest get =
        GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            // Pin the response type and force a download. Without these the browser re-sniffs the
            // body, and a file that passed our allow-list as an image can still be coaxed into
            // rendering as something else on the storage origin.
            .responseContentType(contentType)
            .responseContentDisposition(
                "attachment; filename=\"" + sanitizeFileName(fileName) + "\"")
            .build();

    PresignedGetObjectRequest presigned =
        presigner.presignGetObject(
            GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(get).build());

    return presigned.url().toString();
  }

  @Override
  public Optional<StoredObject> head(String key) {
    try {
      HeadObjectResponse response =
          s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return Optional.of(
          new StoredObject(
              response.contentLength() == null ? 0L : response.contentLength(),
              response.contentType()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return Optional.empty();
      }
      throw new IntegrationException("error.attachment.storage_unavailable", e);
    }
  }

  @Override
  public byte[] readHead(String key, int maxBytes) {
    GetObjectRequest get =
        GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            // Ranged, not whole-object: a signature check needs the first few bytes, and an
            // unbounded read here would turn a large upload into a memory-exhaustion vector.
            .range("bytes=0-" + (maxBytes - 1))
            .build();
    try (ResponseInputStream<GetObjectResponse> stream =
        s3.getObject(get, ResponseTransformer.toInputStream())) {
      return readAtMost(stream, maxBytes);
    } catch (NoSuchKeyException e) {
      return new byte[0];
    } catch (IOException | S3Exception e) {
      throw new IntegrationException("error.attachment.storage_unavailable", e);
    }
  }

  @Override
  public InputStream openStream(String key) {
    try {
      return s3.getObject(
          GetObjectRequest.builder().bucket(bucket).key(key).build(),
          ResponseTransformer.toInputStream());
    } catch (S3Exception e) {
      throw new IntegrationException("error.attachment.storage_unavailable", e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (S3Exception e) {
      // Deletion runs on rejection and retention paths where the caller has already decided the
      // object is unwanted. Failing the request would leave the row and the object disagreeing in
      // the other direction, which is worse: log and let the retention sweep try again.
      log.warn("Failed to delete object key={} from bucket={}", key, bucket, e);
    }
  }

  private static byte[] readAtMost(InputStream stream, int maxBytes) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int total = 0;
    int read;
    while (total < maxBytes
        && (read = stream.read(chunk, 0, Math.min(chunk.length, maxBytes - total))) != -1) {
      buffer.write(chunk, 0, read);
      total += read;
    }
    return buffer.toByteArray();
  }

  /**
   * The file name is client-supplied and lands in a response header. Strip quotes, control
   * characters and CR/LF so it cannot break out of the {@code Content-Disposition} value or inject
   * a header of its own.
   */
  private static String sanitizeFileName(String fileName) {
    String cleaned = fileName.replaceAll("[\\p{Cntrl}\"\\\\]", "");
    return cleaned.isBlank() ? "attachment" : cleaned;
  }
}

