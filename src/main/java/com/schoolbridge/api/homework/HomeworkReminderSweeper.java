package com.schoolbridge.api.homework;

import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import com.schoolbridge.api.tenant.SchoolStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

/**
 * The M9 background sweeper. Two responsibilities:
 *
 * <ol>
 *   <li><b>Reminder fire.</b> Every 5 minutes (default), walks ACTIVE schools that have {@link
 *       SchoolSettings#isHomeworkReminderEnabled()} and whose school-local time is within Â±{@code
 *       reminder-window-minutes} of {@link SchoolSettings#getHomeworkReminderTime()}. For each
 *       qualifying school, finds PUBLISHED {@link HomeworkItem}s due within the horizon whose
 *       {@code reminderSentAt} is null, acquires a Redis SETNX dedup key, then delegates to {@link
 *       HomeworkReminderService#dispatchReminder}.
 *   <li><b>Deferred-release.</b> Every 60 seconds, drains {@link HomeworkRecipient} rows whose
 *       {@code deferredUntil} has passed and dispatches them through the M7 pipeline.
 * </ol>
 */
@Component
@ConditionalOnProperty(
    name = "schoolbridge.homework.sweeper.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class HomeworkReminderSweeper {

  private static final Logger log = LoggerFactory.getLogger(HomeworkReminderSweeper.class);
  private static final String DEDUP_KEY_PREFIX = "homework:reminder_fired:";

  private final HomeworkItemRepository homeworkItems;
  private final HomeworkRecipientRepository recipients;
  private final HomeworkReminderService reminderService;
  private final SchoolRepository schools;
  private final StringRedisTemplate redis;
  private final MeterRegistry meterRegistry;
  private final int reminderWindowMinutes;
  private final int reminderHorizonDays;
  private final Duration dedupTtl;

  public HomeworkReminderSweeper(
      HomeworkItemRepository homeworkItems,
      HomeworkRecipientRepository recipients,
      HomeworkReminderService reminderService,
      SchoolRepository schools,
      StringRedisTemplate redis,
      MeterRegistry meterRegistry,
      @Value("${schoolbridge.homework.sweeper.reminder-window-minutes:15}")
          int reminderWindowMinutes,
      @Value("${schoolbridge.homework.sweeper.reminder-horizon-days:2}") int reminderHorizonDays,
      @Value("${schoolbridge.homework.sweeper.dedup-ttl:36h}") Duration dedupTtl) {
    this.homeworkItems = homeworkItems;
    this.recipients = recipients;
    this.reminderService = reminderService;
    this.schools = schools;
    this.redis = redis;
    this.meterRegistry = meterRegistry;
    this.reminderWindowMinutes = reminderWindowMinutes;
    this.reminderHorizonDays = reminderHorizonDays;
    this.dedupTtl = dedupTtl;
  }

  /**
   * Fires homework reminders for schools whose local time is within the configured window around
   * {@link SchoolSettings#getHomeworkReminderTime()}. The SETNX dedup prevents re-firing the same
   * item within the dedup TTL even if this cron runs every 5 minutes inside the window.
   */
  @Scheduled(cron = "${schoolbridge.homework.sweeper.fire-cron:0 */5 * * * *}")
  public void fireReminders() {
    int page = 0;
    int totalFired = 0;
    Page<School> schoolsPage;
    do {
      schoolsPage = schools.findByStatus(SchoolStatus.ACTIVE, PageRequest.of(page, 50));
      for (School school : schoolsPage.getContent()) {
        totalFired += sweepSchool(school);
      }
      page++;
    } while (schoolsPage.hasNext());
    if (totalFired > 0) {
      log.info("homework_reminder_sweep_complete fired={}", totalFired);
    }
  }

  /**
   * Releases every deferred recipient whose quiet-hours hold has closed. Runs every 60 seconds;
   * mirrors {@link com.schoolbridge.api.attendance.AttendanceSweeper#releaseDeferredAlerts()}.
   */
  @Scheduled(fixedDelayString = "${schoolbridge.homework.sweeper.deferred-release-rate:PT1M}")
  public void releaseDeferredReminders() {
    Instant now = Instant.now();
    List<HomeworkRecipient> due = recipients.findDeferredReadyToDispatch(now);
    if (due.isEmpty()) {
      return;
    }
    int released = 0;
    for (HomeworkRecipient row : due) {
      UUID schoolId = row.getSchoolId();
      UUID recipientId = row.getId();
      try {
        TenantContext.runAs(
            schoolId,
            () -> {
              reminderService.releaseDeferred(recipientId);
              return null;
            });
        released++;
      } catch (RuntimeException ex) {
        log.error(
            "homework_reminder_release_failed recipientId={} schoolId={} cause={}",
            recipientId,
            schoolId,
            ex.getClass().getSimpleName() + ": " + ex.getMessage(),
            ex);
      }
    }
    log.info("homework_reminder_deferred_released count={}", released);
  }

  int sweepSchool(School school) {
    if (!school.getSettings().isHomeworkReminderEnabled()) {
      return 0;
    }
    ZoneId zone;
    try {
      zone = ZoneId.of(school.getTimezone());
    } catch (RuntimeException ex) {
      log.warn(
          "homework_reminder_school_skipped schoolId={} reason=invalid_timezone tz={}",
          school.getId(),
          school.getTimezone());
      return 0;
    }

    ZonedDateTime nowZoned = ZonedDateTime.ofInstant(Instant.now(), zone);
    LocalTime nowLocal = nowZoned.toLocalTime();
    LocalTime target = school.getSettings().getHomeworkReminderTime();
    long minutesFromTarget = Math.abs(ChronoUnit.MINUTES.between(target, nowLocal));
    // Wrap-around midnight: 23:58 is 2 minutes from 00:00.
    if (minutesFromTarget > 12 * 60) {
      minutesFromTarget = 24 * 60 - minutesFromTarget;
    }
    if (minutesFromTarget > reminderWindowMinutes) {
      return 0;
    }

    LocalDate today = nowZoned.toLocalDate();
    LocalDate horizon = today.plusDays(reminderHorizonDays);
    UUID schoolId = school.getId();

    return TenantContext.runAs(
        schoolId,
        () -> {
          List<HomeworkItem> candidates = homeworkItems.findPublishedDueForReminder(today, horizon);
          int fired = 0;
          for (HomeworkItem item : candidates) {
            UUID homeworkId = item.getId();
            if (!acquireDedup(homeworkId, today)) {
              continue;
            }
            try {
              reminderService.dispatchReminder(homeworkId);
              fired++;
            } catch (RuntimeException ex) {
              log.error(
                  "homework_reminder_fire_failed homeworkId={} schoolId={} cause={}",
                  homeworkId,
                  schoolId,
                  ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                  ex);
            }
          }
          if (fired > 0) {
            meterRegistry
                .counter("homework.reminder.fired_schools", "schoolId", schoolId.toString())
                .increment(fired);
          }
          return fired;
        });
  }

  private boolean acquireDedup(UUID homeworkId, LocalDate date) {
    String key = DEDUP_KEY_PREFIX + homeworkId + ":" + date;
    Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", dedupTtl);
    return Boolean.TRUE.equals(acquired);
  }
}
