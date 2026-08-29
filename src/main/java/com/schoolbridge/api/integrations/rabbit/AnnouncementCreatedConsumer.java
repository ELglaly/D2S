package com.schoolbridge.api.integrations.rabbit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.integrations.AnnouncementSendService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code announcement.created} events off RabbitMQ and dispatches each materialized
 * recipient via {@link AnnouncementSendService}.
 *
 * <p>The consumer binds the tenant on every message via {@link TenantContext#runAs} (open-question
 * (2), option (a)) so Hibernate's {@code tenantFilter} is active during recipient loads. This is
 * the cross-tenant safety guarantee: a message for school A can only touch school A's recipients.
 */
@Component
@ConditionalOnProperty(name = "schoolbridge.outbox.relay.enabled", havingValue = "true")
public class AnnouncementCreatedConsumer {

  private static final Logger log = LoggerFactory.getLogger(AnnouncementCreatedConsumer.class);

  private final AnnouncementSendService sendService;
  private final ObjectMapper objectMapper;

  public AnnouncementCreatedConsumer(
      AnnouncementSendService sendService, ObjectMapper objectMapper) {
    this.sendService = sendService;
    this.objectMapper = objectMapper;
  }

  @RabbitListener(queues = "${schoolbridge.rabbitmq.queues.announcement-created}")
  public void onMessage(byte[] body) {
    JsonNode payload;
    try {
      payload = objectMapper.readTree(body);
    } catch (Exception ex) {
      log.error("announcement_created_unparseable bodyLen={}", body == null ? 0 : body.length, ex);
      return;
    }
    UUID schoolId = UUID.fromString(payload.get("schoolId").asText());
    UUID announcementId = UUID.fromString(payload.get("announcementId").asText());
    Language language = Language.valueOf(payload.get("language").asText());
    String text = payload.get("body").asText();

    TenantContext.runAs(
        schoolId,
        () -> {
          sendService.dispatchCreated(announcementId, language, text);
          return null;
        });
  }
}

