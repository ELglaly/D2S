package com.schoolbridge.api.common.outbox;

/**
 * Port that publishes an outbox event to the message broker. The RabbitMQ implementation is
 * supplied by the messaging/integration modules; until then the relay stays disabled ({@code
 * schoolbridge.outbox.relay.enabled=false}).
 */
public interface OutboxPublisher {

  void publish(OutboxEvent event);
}
