package com.schoolbridge.api.integrations.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.schoolbridge.api.common.outbox.OutboxEvent;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Verifies the routing-key prefix mapping in {@link RabbitOutboxPublisher} without standing up
 * RabbitMQ. {@code announcement.*} events go to {@code schoolbridge.announcements}, {@code otp.*}
 * to {@code schoolbridge.otp}, and the routing key is the full {@code eventType}.
 */
@ExtendWith(MockitoExtension.class)
class RabbitOutboxPublisherTest {

  @Mock RabbitTemplate rabbitTemplate;

  @Test
  void publish_announcementCreated_routesToAnnouncementsExchange() throws Exception {
    RabbitOutboxPublisher publisher = new RabbitOutboxPublisher(rabbitTemplate);
    OutboxEvent event =
        eventWithId(
            new OutboxEvent(
                UUID.randomUUID(),
                "Announcement",
                UUID.randomUUID(),
                "announcement.created",
                "{\"foo\":\"bar\"}"));

    publisher.publish(event);

    ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate)
        .send(eq("schoolbridge.announcements"), eq("announcement.created"), message.capture());
    assertThat(message.getValue().getMessageProperties().getHeaders())
        .containsEntry("eventType", "announcement.created")
        .containsEntry("aggregateType", "Announcement");
  }

  @Test
  void publish_otpSend_routesToOtpExchange() throws Exception {
    RabbitOutboxPublisher publisher = new RabbitOutboxPublisher(rabbitTemplate);
    OutboxEvent event =
        eventWithId(
            new OutboxEvent(
                UUID.randomUUID(), "Otp", UUID.randomUUID(), "otp.send", "{\"code\":\"hashed\"}"));

    publisher.publish(event);

    verify(rabbitTemplate)
        .send(
            eq("schoolbridge.otp"),
            eq("otp.send"),
            org.mockito.ArgumentMatchers.any(Message.class));
  }

  @Test
  void publish_unknownPrefix_routesToConventionExchangeName() throws Exception {
    RabbitOutboxPublisher publisher = new RabbitOutboxPublisher(rabbitTemplate);
    OutboxEvent event =
        eventWithId(
            new OutboxEvent(UUID.randomUUID(), "Other", UUID.randomUUID(), "other.thing", "{}"));

    publisher.publish(event);

    verify(rabbitTemplate)
        .send(
            eq("schoolbridge.other"),
            eq("other.thing"),
            org.mockito.ArgumentMatchers.any(Message.class));
  }

  /**
   * The {@code @UuidGenerator} only fires on persist; unit-test instances therefore start with a
   * null id. Inject one via reflection so the publisher's header serialization sees a real value.
   */
  private static OutboxEvent eventWithId(OutboxEvent event) throws Exception {
    Field idField = OutboxEvent.class.getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(event, UUID.randomUUID());
    return event;
  }
}
