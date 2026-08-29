package com.schoolbridge.api.integrations.rabbit;

import com.schoolbridge.api.common.outbox.OutboxEvent;
import com.schoolbridge.api.common.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Routes outbox events to topic exchanges based on the event-type prefix:
 *
 * <ul>
 *   <li>{@code announcement.*} â†’ {@code schoolbridge.announcements}
 *   <li>{@code otp.*} â†’ {@code schoolbridge.otp} (reserved; M7 keeps OTP in-process per the
 *       handoff)
 * </ul>
 *
 * The full event type is the routing key, the JSON payload (already serialized by {@code
 * OutboxEventRecorder}) is the body. Tenant and aggregate ids are included as message headers so
 * consumers can index without parsing the body.
 *
 * <p>Only loaded when the relay is enabled â€” keeps test slices clean.
 */
@Component
@ConditionalOnProperty(name = "schoolbridge.outbox.relay.enabled", havingValue = "true")
public class RabbitOutboxPublisher implements OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(RabbitOutboxPublisher.class);

  private final RabbitTemplate rabbitTemplate;

  public RabbitOutboxPublisher(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  @Override
  public void publish(OutboxEvent event) {
    String exchange = exchangeFor(event.getEventType());
    org.springframework.amqp.core.Message message =
        org.springframework.amqp.core.MessageBuilder.withBody(
                event.getPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .setContentEncoding("UTF-8")
            .setHeader("schoolId", event.getSchoolId().toString())
            .setHeader("aggregateType", event.getAggregateType())
            .setHeader("aggregateId", event.getAggregateId().toString())
            .setHeader("eventType", event.getEventType())
            .setHeader("outboxId", event.getId().toString())
            .build();
    rabbitTemplate.send(exchange, event.getEventType(), message);
    log.debug(
        "outbox_published exchange={} routingKey={} outboxId={}",
        exchange,
        event.getEventType(),
        event.getId());
  }

  private static String exchangeFor(String eventType) {
    int dot = eventType.indexOf('.');
    String prefix = dot < 0 ? eventType : eventType.substring(0, dot);
    return switch (prefix) {
      case "announcement" -> "schoolbridge.announcements";
      case "otp" -> "schoolbridge.otp";
      case "attendance" -> "schoolbridge.attendance";
      default -> "schoolbridge." + prefix;
    };
  }
}

