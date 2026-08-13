package com.schoolbridge.api;

import java.net.URI;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Base for integration tests. Uses the <em>singleton container</em> pattern: containers are started
 * once per JVM in the static initializer and never stopped, so {@code @ServiceConnection} bindings
 * stay valid across every {@code @SpringBootTest} class in the test run. Without this, the JUnit
 * {@code @Container} extension tears containers down between classes, which invalidates the
 * connection details Boot has already wired into cached contexts.
 */
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  // pgvector image (postgres:16 + the 'vector' extension) so Liquibase changeset
  // 013-pgvector-extension can run CREATE EXTENSION vector at context startup. Declared compatible
  // with the postgres image so @ServiceConnection / PostgreSQLContainer still bind normally.
  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @ServiceConnection
  static final RabbitMQContainer RABBITMQ =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));

  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

  // MinIO backs the attachment pipeline. There is no @ServiceConnection for it, so the endpoint and
  // credentials are pushed in via @DynamicPropertySource below — and they have to be, because a
  // presigned URL's signature covers the host and the container's port is only known at runtime.
  static final MinIOContainer MINIO =
      new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-06-13T22-53-53Z"));

  static final String TEST_BUCKET = "schoolbridge-test";

  static {
    POSTGRES.start();
    RABBITMQ.start();
    REDIS.start();
    MINIO.start();
    createTestBucket();
  }

  @DynamicPropertySource
  static void storageProperties(DynamicPropertyRegistry registry) {
    registry.add("schoolbridge.storage.endpoint", MINIO::getS3URL);
    registry.add("schoolbridge.storage.access-key", MINIO::getUserName);
    registry.add("schoolbridge.storage.secret-key", MINIO::getPassword);
    registry.add("schoolbridge.storage.bucket", () -> TEST_BUCKET);
    // MinIO on a bare host has no wildcard DNS, so virtual-host addressing does not resolve.
    registry.add("schoolbridge.storage.path-style-access", () -> true);
  }

  private static void createTestBucket() {
    try (S3Client client =
        S3Client.builder()
            .endpointOverride(URI.create(MINIO.getS3URL()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()) {
      client.createBucket(CreateBucketRequest.builder().bucket(TEST_BUCKET).build());
    } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException ignored) {
      // Containers are singletons per JVM, but a re-run against a reused container is fine.
    }
  }
}
