package com.schoolbridge.api.integrations.rabbit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.attendance.AttendanceAlertService;
import com.schoolbridge.api.attendance.AttendanceStatus;
import com.schoolbridge.api.common.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes the three attendance alert routing keys ({@code attendance.absent_alert} / {@code
 * attendance.late_alert} / {@code attendance.excused_alert}) and hands each off to {@link
 * AttendanceAlertService}. Each method:
 *
 * <ol>
 *   <li>parses the outbox payload (HashMap-with-nullable-fields per
 *       [[feedback-outbox-audit-mapof-npe]]);
 *   <li>restores the producer-side {@code traceId} into MDC so the consumer-side log lines
 *       correlate with the original {@code POST /attendance/mark} span;
 *   <li>binds {@link TenantContext#runAs} so {@code @Filter} applies during recipient loads â€” the
 *       cross-tenant safety guarantee mirrored from M7's announcement consumer;
 *   <li>records {@code attendance.alert.latency} ({@code markedAt} â†’ "consumer finished
 *       dispatching") which is the NFR-P2 SLA dashboard metric â€” explicitly bucketed to make the
 *       60 s p95 threshold visible.
 * </ol>
 *
 * <p>Only loaded when {@code schoolbridge.outbox.relay.enabled=true}, mirroring M7.
 */
@Component
@ConditionalOnProperty(name = "schoolbridge.outbox.relay.enabled", havingValue = "true")
public class AttendanceAlertConsumer {

  private static final Logger log = LoggerFactory.getLogger(AttendanceAlertConsumer.class);

  private final AttendanceAlertService alertService;
  private final ObjectMapper objectMapper;
  private final Timer latencyTimer;

  public AttendanceAlertConsumer(
      AttendanceAlertService alertService, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
    this.alertService = alertService;
    this.objectMapper = objectMapper;
    this.latencyTimer =
        Timer.builder("attendance.alert.latency")
            .description("Time from teacher mark to end of consumer fan-out, per attendance record")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .serviceLevelObjectives(
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofMinutes(5))
            .register(meterRegistry);
  }

  @RabbitListener(queues = "${schoolbridge.rabbitmq.queues.attendance-absent-alert}")
  public void onAbsentAlert(byte[] body) {
    handle(body, AttendanceStatus.ABSENT);
  }

  @RabbitListener(queues = "${schoolbridge.rabbitmq.queues.attendance-late-alert}")
  public void onLateAlert(byte[] body) {
    handle(body, AttendanceStatus.LATE);
  }

  @RabbitListener(queues = "${schoolbridge.rabbitmq.queues.attendance-excused-alert}")
  public void onExcusedAlert(byte[] body) {
    handle(body, AttendanceStatus.EXCUSED);
  }

  /** Visible for direct invocation by integration tests that don't want to spin up Rabbit. */
  public void handle(byte[] body, AttendanceStatus triggeringStatus) {
    JsonNode payload;
    try {
      payload = objectMapper.readTree(body);
    } catch (Exception ex) {
      log.error(
          "attendance_alert_unparseable status={} bodyLen={}",
          triggeringStatus,
          body == null ? 0 : body.length,
          ex);
      return;
    }
    UUID schoolId = UUID.fromString(payload.get("schoolId").asText());
    UUID recordId = UUID.fromString(payload.get("recordId").asText());
    String traceId = payload.hasNonNull("traceId") ? payload.get("traceId").asText() : null;
    Instant markedAt =
        payload.hasNonNull("markedAt") ? Instant.parse(payload.get("markedAt").asText()) : null;

    String priorTraceId = traceId == null ? null : MDC.get("traceId");
    if (traceId != null) {
      MDC.put("traceId", traceId);
    }
    try {
      TenantContext.runAs(
          schoolId,
          () -> {
            alertService.dispatchAlert(recordId, triggeringStatus);
            return null;
          });
      if (markedAt != null) {
        long elapsedMs = Math.max(0, Instant.now().toEpochMilli() - markedAt.toEpochMilli());
        latencyTimer.record(elapsedMs, TimeUnit.MILLISECONDS);
      }
    } finally {
      if (traceId != null) {
        if (priorTraceId == null) {
          MDC.remove("traceId");
        } else {
          MDC.put("traceId", priorTraceId);
        }
      }
    }
  }
}
