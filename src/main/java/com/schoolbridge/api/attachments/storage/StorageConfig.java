package com.schoolbridge.api.attachments.storage;

import com.schoolbridge.api.attachments.StorageProperties;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 client and presigner.
 *
 * <p>Both beans are built eagerly but connect lazily, so a profile with no reachable storage (the
 * default test profile) still starts â€” the failure surfaces on the first attachment call rather
 * than at boot, which is the right trade for a subsystem most tests never touch.
 *
 * <p>When no static access key is configured the SDK's default provider chain takes over, so an
 * instance role or web-identity token works in production without any credential in the
 * environment. That is the preferred production shape.
 */
@Configuration
public class StorageConfig {

  @Bean
  public S3Client s3Client(StorageProperties properties) {
    var builder =
        S3Client.builder()
            .region(Region.of(properties.getRegion()))
            .credentialsProvider(credentials(properties))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(properties.isPathStyleAccess())
                    .build());
    if (!properties.getEndpoint().isBlank()) {
      builder.endpointOverride(URI.create(properties.getEndpoint()));
    }
    return builder.build();
  }

  @Bean
  public S3Presigner s3Presigner(StorageProperties properties) {
    var builder =
        S3Presigner.builder()
            .region(Region.of(properties.getRegion()))
            .credentialsProvider(credentials(properties))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(properties.isPathStyleAccess())
                    .build());
    if (!properties.getEndpoint().isBlank()) {
      // The signature covers the host, so the presigner must be pointed at the same endpoint the
      // client will actually call. Signing against the default AWS host and handing out a MinIO
      // URL produces a 403 that reads like a credentials problem and is not one.
      builder.endpointOverride(URI.create(properties.getEndpoint()));
    }
    return builder.build();
  }

  private static AwsCredentialsProvider credentials(StorageProperties properties) {
    String accessKey = properties.getAccessKey();
    String secretKey = properties.getSecretKey();
    if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
      return DefaultCredentialsProvider.create();
    }
    return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
  }
}
