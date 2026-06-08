package com.schoolbridge.api.integrations.rabbit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.integrations.AnnouncementSendService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code announcement.recalled} events. Per open question (4) / option (b) in {@code
 * HANDOFF_M7.md}, M7 does NOT send a follow-up "recalled" template — it only marks not-yet-
 * delivered recipients FAILED to stop further fan-out attempts.
 */
@Component
@ConditionalOnProperty(name = "schoolbridge.outbox.relay.enabled", havingValue = "true")
public class AnnouncementRecalledConsumer {

  private static final Logger log = LoggerFactory.getLogger(AnnouncementRecalledConsumer.class);

  private final AnnouncementSendService sendService;
  private final ObjectMapper objectMapper;

  public AnnouncementRecalledConsumer(
      AnnouncementSendService sendService, ObjectMapper objectMapper) {
    this.sendService = sendService;
    this.objectMapper = objectMapper;
  }

  @RabbitListener(queues = "${schoolbridge.rabbitmq.queues.announcement-recalled}")
  public void onMessage(byte[] body) {
    JsonNode payload;
    try {
      payload = objectMapper.readTree(body);
    } catch (Exception ex) {
      log.error("announcement_recalled_unparseable bodyLen={}", body == null ? 0 : body.length, ex);
      return;
    }
    UUID schoolId = UUID.fromString(payload.get("schoolId").asText());
    UUID announcementId = UUID.fromString(payload.get("announcementId").asText());

    TenantContext.runAs(
        schoolId,
        () -> {
          sendService.dispatchRecalled(announcementId);
          return null;
        });
  }
}
