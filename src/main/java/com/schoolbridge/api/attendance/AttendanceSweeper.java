package com.schoolbridge.api.attendance;

import com.schoolbridge.api.classes.entity.SchoolClass;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.common.outbox.OutboxEventRecorder;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The M8 background sweeper. Two responsibilities, kept in one bean to minimize cron footprint:
 *
 * <ol>
 *   <li><b>Deferred-release.</b> Every minute, drain {@link AttendanceAlertRecipient} rows whose
 *       {@code deferredUntil} has passed and dispatch them through the M7 pipeline. This is the
 *       partner of the quiet-hours branch in {@link AttendanceAlertService#dispatchAlert}.
 *   <li><b>Missed-roster detection.</b> Every 15 minutes, walk active schools and emit one {@code
 *       attendance.roster_missed} outbox event per (class, today) tuple that has no attendance row
 *       submitted by the school's local {@link SchoolSettings#getRosterDueByLocalTime()}.
 *       Idempotent across runs within a school day via Redis {@code SETNX} on a {@code
 *       attendance:roster_missed:{schoolId}:{classId}:{date}} key with a configurable TTL.
 * </ol>
 *
 * <p>No queue is bound to {@code attendance.roster_missed} in M8 — the event lands on the topic
 * exchange and falls through. A Micrometer counter {@code attendance.roster_missed} tagged by
 * {@code schoolId} is the live signal until M12 reporting wires a consumer.
 */
@Component
@ConditionalOnProperty(
    name = "schoolbridge.attendance.sweeper.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AttendanceSweeper {

  private static final Logger log = LoggerFactory.getLogger(AttendanceSweeper.class);
  private static final String AGGREGATE_TYPE = "AttendanceRecord";
  private static final String EVENT_ROSTER_MISSED = "attendance.roster_missed";
  private static final String DEDUP_KEY_PREFIX = "attendance:roster_missed:";

  private final AttendanceAlertRecipientRepository recipients;
  private final AttendanceRecordRepository records;
  private final AttendanceAlertService alertService;
  private final SchoolRepository schools;
  private final SchoolClassRepository classes;
  private final OutboxEventRecorder outbox;
  private final StringRedisTemplate redis;
  private final MeterRegistry meterRegistry;
  private final TransactionTemplate tx;
  private final Duration missedRosterDedupTtl;

  public AttendanceSweeper(
      AttendanceAlertRecipientRepository recipients,
      AttendanceRecordRepository records,
      AttendanceAlertService alertService,
      SchoolRepository schools,
      SchoolClassRepository classes,
      OutboxEventRecorder outbox,
      StringRedisTemplate redis,
      MeterRegistry meterRegistry,
      TransactionTemplate tx,
      @Value("${schoolbridge.attendance.sweeper.missed-roster-dedup-ttl:36h}")
          Duration missedRosterDedupTtl) {
    this.recipients = recipients;
    this.records = records;
    this.alertService = alertService;
    this.schools = schools;
    this.classes = classes;
    this.outbox = outbox;
    this.redis = redis;
    this.meterRegistry = meterRegistry;
    this.tx = tx;
    this.missedRosterDedupTtl = missedRosterDedupTtl;
  }

  /**
   * Releases every deferred recipient whose hold window has closed. The repository call runs
   * without a {@link TenantContext} (filter inactive → cross-tenant query), and per-recipient
   * release is then re-bound to the row's school so {@link AttendanceAlertService#releaseDeferred}
   * sees only that tenant's data.
   */
  @Scheduled(fixedDelayString = "${schoolbridge.attendance.sweeper.deferred-release-rate:PT1M}")
  public void releaseDeferredAlerts() {
    Instant now = Instant.now();
    List<AttendanceAlertRecipient> due = recipients.findDeferredReadyToDispatch(now);
    if (due.isEmpty()) {
      return;
    }
    int released = 0;
    for (AttendanceAlertRecipient row : due) {
      UUID schoolId = row.getSchoolId();
      UUID recipientId = row.getId();
      try {
        TenantContext.runAs(
            schoolId,
            () -> {
              alertService.releaseDeferred(recipientId);
              return null;
            });
        released++;
      } catch (RuntimeException ex) {
        log.error(
            "attendance_alert_release_failed recipientId={} schoolId={} cause={}",
            recipientId,
            schoolId,
            ex.getClass().getSimpleName() + ": " + ex.getMessage(),
            ex);
      }
    }
    log.info("attendance_alert_deferred_released count={}", released);
  }

  /**
   * Walks active schools page-by-page; for each school whose local now is past the school's roster
   * due-by, emits one {@code attendance.roster_missed} per class with no records for today.
   * Idempotent through Redis SETNX.
   */
  @Scheduled(cron = "${schoolbridge.attendance.sweeper.missed-roster-cron:0 */15 * * * *}")
  public void detectMissedRosters() {
    int page = 0;
    int totalEmitted = 0;
    Page<School> schoolsPage;
    do {
      schoolsPage = schools.findByStatus(SchoolStatus.ACTIVE, PageRequest.of(page, 50));
      for (School school : schoolsPage.getContent()) {
        totalEmitted += sweepSchool(school);
      }
      page++;
    } while (schoolsPage.hasNext());
    if (totalEmitted > 0) {
      log.info("attendance_missed_roster_swept emitted={}", totalEmitted);
    }
  }

  int sweepSchool(School school) {
    ZoneId zone;
    try {
      zone = ZoneId.of(school.getTimezone());
    } catch (RuntimeException ex) {
      log.warn(
          "attendance_missed_roster_school_skipped schoolId={} reason=invalid_timezone tz={}",
          school.getId(),
          school.getTimezone());
      return 0;
    }
    ZonedDateTime nowLocal = ZonedDateTime.ofInstant(Instant.now(), zone);
    LocalTime dueBy = school.getSettings().getRosterDueByLocalTime();
    if (nowLocal.toLocalTime().isBefore(dueBy)) {
      return 0;
    }
    LocalDate today = nowLocal.toLocalDate();
    UUID schoolId = school.getId();

    return TenantContext.runAs(
        schoolId,
        () -> {
          int emitted = 0;
          int classPage = 0;
          Page<SchoolClass> classesPage;
          do {
            classesPage = classes.findAll(PageRequest.of(classPage, 100));
            for (SchoolClass c : classesPage.getContent()) {
              if (records.findAllByClassIdAndDate(c.getId(), today).isEmpty()
                  && reserveDedup(schoolId, c.getId(), today)) {
                emitRosterMissed(schoolId, c.getId(), c.getName(), today);
                emitted++;
              }
            }
            classPage++;
          } while (classesPage.hasNext());
          return emitted;
        });
  }

  private boolean reserveDedup(UUID schoolId, UUID classId, LocalDate date) {
    String key = DEDUP_KEY_PREFIX + schoolId + ":" + classId + ":" + date;
    Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", missedRosterDedupTtl);
    return Boolean.TRUE.equals(acquired);
  }

  private void emitRosterMissed(UUID schoolId, UUID classId, String className, LocalDate date) {
    tx.executeWithoutResult(
        s -> {
          Map<String, Object> payload = new HashMap<>();
          payload.put("schoolId", schoolId);
          payload.put("classId", classId);
          payload.put("className", className);
          payload.put("date", date.toString());
          payload.put("detectedAt", Instant.now().toString());
          outbox.record(schoolId, AGGREGATE_TYPE, classId, EVENT_ROSTER_MISSED, payload);
        });
    meterRegistry.counter("attendance.roster_missed", "schoolId", schoolId.toString()).increment();
  }
}
