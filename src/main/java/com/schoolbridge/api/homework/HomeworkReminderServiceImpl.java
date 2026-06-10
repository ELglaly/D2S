package com.schoolbridge.api.homework;

import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.attendance.QuietHoursCalculator;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.integrations.DispatchRequest;
import com.schoolbridge.api.integrations.DispatchResult;
import com.schoolbridge.api.integrations.NotificationDispatcher;
import com.schoolbridge.api.integrations.whatsapp.TemplateParam;
import com.schoolbridge.api.integrations.whatsapp.WhatsAppProperties;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fans out a homework reminder to every linked parent whose recipient row is still PENDING. Mirrors
 * {@link com.schoolbridge.api.attendance.AttendanceAlertService} with two homework-specific
 * behaviors:
 *
 * <ul>
 *   <li>{@link HomeworkItem#markReminderSent} is stamped immediately <em>before</em> the
 *       per-recipient loop so that a concurrent sweeper tick cannot re-enter the same item while
 *       dispatch is in progress.
 *   <li>Quiet-hours are controlled by the same {@link SchoolSettings#isAlertsRespectQuietHours()}
 *       flag as attendance alerts — one knob per school.
 * </ul>
 */
@Service
public class HomeworkReminderServiceImpl implements HomeworkReminderService {

  private static final Logger log = LoggerFactory.getLogger(HomeworkReminderServiceImpl.class);
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
  private static final String METRIC_REMINDER_SENT = "homework.reminder.sent";

  private final HomeworkItemRepository homeworkItems;
  private final HomeworkRecipientRepository recipients;
  private final SchoolRepository schools;
  private final UserRepository users;
  private final NotificationDispatcher dispatcher;
  private final WhatsAppProperties whatsAppProperties;
  private final MessageSource messageSource;
  private final MeterRegistry meterRegistry;

  public HomeworkReminderServiceImpl(
      HomeworkItemRepository homeworkItems,
      HomeworkRecipientRepository recipients,
      SchoolRepository schools,
      UserRepository users,
      NotificationDispatcher dispatcher,
      WhatsAppProperties whatsAppProperties,
      MessageSource messageSource,
      MeterRegistry meterRegistry) {
    this.homeworkItems = homeworkItems;
    this.recipients = recipients;
    this.schools = schools;
    this.users = users;
    this.dispatcher = dispatcher;
    this.whatsAppProperties = whatsAppProperties;
    this.messageSource = messageSource;
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Transactional
  public void dispatchReminder(UUID homeworkId) {
    HomeworkItem item = homeworkItems.findById(homeworkId).orElse(null);
    if (item == null || item.getStatus() != HomeworkStatus.PUBLISHED) {
      log.warn("homework_reminder_skipped homeworkId={} reason=not_published", homeworkId);
      return;
    }

    School school = schools.findById(item.getSchoolId()).orElse(null);
    if (school == null) {
      log.warn(
          "homework_reminder_school_missing homeworkId={} schoolId={}",
          homeworkId,
          item.getSchoolId());
      return;
    }
    SchoolSettings settings = school.getSettings();
    ZoneId zone = ZoneId.of(school.getTimezone());
    Instant now = Instant.now();
    boolean quietHoursApply =
        settings.isAlertsRespectQuietHours()
            && QuietHoursCalculator.isInQuietWindow(
                now, zone, settings.getQuietHoursStart(), settings.getQuietHoursEnd());
    Instant deferredUntil =
        quietHoursApply
            ? QuietHoursCalculator.nextEndOfWindow(
                now, zone, settings.getQuietHoursStart(), settings.getQuietHoursEnd())
            : null;

    // Stamp before the loop so a concurrent sweep tick cannot re-enter this item.
    item.markReminderSent(now);

    Language language = toDispatchLanguage(settings.getDefaultLanguage());
    String templateName = whatsAppProperties.getTemplate().getHomeworkReminderName();
    String dueDateText = DATE_FORMAT.format(item.getDueDate());

    List<HomeworkRecipient> recipientList = recipients.findAllByHomeworkId(homeworkId);
    int sent = 0;
    int deferred = 0;
    int skipped = 0;

    for (HomeworkRecipient row : recipientList) {
      HomeworkDeliveryStatus status = row.getDeliveryStatus();
      if (status == HomeworkDeliveryStatus.SENT || status == HomeworkDeliveryStatus.FAILED) {
        skipped++;
        continue;
      }
      if (status == HomeworkDeliveryStatus.DEFERRED) {
        deferred++;
        continue;
      }
      if (quietHoursApply) {
        row.markDeferred(deferredUntil);
        deferred++;
        continue;
      }
      dispatchOne(row, item.getSubject(), dueDateText, templateName, language);
      if (row.getDeliveryStatus() == HomeworkDeliveryStatus.SENT) {
        sent++;
      }
    }

    meterRegistry
        .counter(
            METRIC_REMINDER_SENT,
            "schoolId",
            item.getSchoolId().toString(),
            "status",
            quietHoursApply && deferred > 0 ? "deferred" : "ok")
        .increment();

    log.info(
        "homework_reminder_dispatched homeworkId={} sent={} deferred={} skipped={}",
        homeworkId,
        sent,
        deferred,
        skipped);
  }

  @Override
  @Transactional
  public void releaseDeferred(UUID recipientId) {
    HomeworkRecipient row = recipients.findById(recipientId).orElse(null);
    if (row == null || row.getDeliveryStatus() != HomeworkDeliveryStatus.DEFERRED) {
      return;
    }
    HomeworkItem item = homeworkItems.findById(row.getHomeworkId()).orElse(null);
    if (item == null || item.getStatus() != HomeworkStatus.PUBLISHED) {
      row.markFailed("homework_not_published");
      log.warn(
          "homework_reminder_release_skipped recipientId={} reason=not_published", recipientId);
      return;
    }

    School school = schools.findById(row.getSchoolId()).orElse(null);
    Language language =
        school != null
            ? toDispatchLanguage(school.getSettings().getDefaultLanguage())
            : Language.EN;

    row.releaseFromDeferral();
    dispatchOne(
        row,
        item.getSubject(),
        DATE_FORMAT.format(item.getDueDate()),
        whatsAppProperties.getTemplate().getHomeworkReminderName(),
        language);

    log.info(
        "homework_reminder_deferred_released recipientId={} status={}",
        recipientId,
        row.getDeliveryStatus());
  }

  private void dispatchOne(
      HomeworkRecipient row,
      String subject,
      String dueDateText,
      String templateName,
      Language language) {
    User parent = users.findById(row.getParentUserId()).orElse(null);
    if (parent == null) {
      row.markFailed("parent_user_missing");
      return;
    }
    if (parent.getPhone() == null || parent.getPhone().isBlank()) {
      row.markFailed("parent_phone_missing");
      return;
    }

    String smsBody = renderSmsBody(language, subject, dueDateText);
    DispatchRequest request =
        new DispatchRequest(
            parent.getPhone(),
            language,
            templateName,
            List.of(TemplateParam.of(subject), TemplateParam.of(dueDateText)),
            smsBody);
    try {
      DispatchResult result = dispatcher.dispatch(request);
      if (result.accepted() && result.messageId() != null) {
        row.markSent(result.messageId());
      } else {
        row.markFailed("dispatch_not_accepted");
      }
    } catch (RuntimeException ex) {
      log.warn(
          "homework_reminder_dispatch_failed recipientId={} cause={}",
          row.getId(),
          ex.getClass().getSimpleName() + ": " + ex.getMessage());
      row.markFailed(ex.getClass().getSimpleName());
    }
  }

  private String renderSmsBody(Language language, String subject, String dueDateText) {
    Locale locale = language == Language.AR ? Locale.of("ar") : Locale.ENGLISH;
    return messageSource.getMessage(
        "notification.whatsapp.template.homework_reminder",
        new Object[] {subject, dueDateText},
        "Homework: " + subject + ". Due " + dueDateText + ".",
        locale);
  }

  private static Language toDispatchLanguage(com.schoolbridge.api.tenant.Language source) {
    return switch (source) {
      case AR -> Language.AR;
      case EN -> Language.EN;
    };
  }
}
