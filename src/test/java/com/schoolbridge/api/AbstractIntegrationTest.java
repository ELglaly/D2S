package com.schoolbridge.api;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests. Uses the <em>singleton container</em> pattern: containers are started
 * once per JVM in the static initializer and never stopped, so {@code @ServiceConnection} bindings
 * stay valid across every {@code @SpringBootTest} class in the test run. Without this, the JUnit
 * {@code @Container} extension tears containers down between classes, which invalidates the
 * connection details Boot has already wired into cached contexts.
 */
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  @ServiceConnection
  static final RabbitMQContainer RABBITMQ =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));

  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

  static {
    POSTGRES.start();
    RABBITMQ.start();
    REDIS.start();
  }
}
