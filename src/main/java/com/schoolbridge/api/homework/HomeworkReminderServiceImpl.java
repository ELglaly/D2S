package com.schoolbridge.api.homework;

import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.integrations.DispatchRequest;
import com.schoolbridge.api.integrations.DispatchResult;
import com.schoolbridge.api.integrations.NotificationChannel;
import com.schoolbridge.api.integrations.NotificationDispatcher;
import com.schoolbridge.api.integrations.NotificationTarget;
import com.schoolbridge.api.integrations.UserDispatchRequest;
import com.schoolbridge.api.integrations.whatsapp.TemplateParam;
import com.schoolbridge.api.integrations.whatsapp.WhatsAppProperties;
import com.schoolbridge.api.notifications.NotificationCategory;
import com.schoolbridge.api.notifications.NotificationDecision;
import com.schoolbridge.api.notifications.NotificationPreferenceService;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *   <li>Quiet hours and opt-out are decided <em>per parent</em> by {@link
 *       NotificationPreferenceService}, not by the school-wide flag. A parent who never touched
 *       their preferences still gets the school's behaviour, because that flag is what the resolver
 *       falls back to — so this is a widening, not a change, for existing schools.
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
  private final NotificationPreferenceService preferences;
  private final WhatsAppProperties whatsAppProperties;
  private final MessageSource messageSource;
  private final MeterRegistry meterRegistry;

  public HomeworkReminderServiceImpl(
      HomeworkItemRepository homeworkItems,
      HomeworkRecipientRepository recipients,
      SchoolRepository schools,
      UserRepository users,
      NotificationDispatcher dispatcher,
      NotificationPreferenceService preferences,
      WhatsAppProperties whatsAppProperties,
      MessageSource messageSource,
      MeterRegistry meterRegistry) {
    this.homeworkItems = homeworkItems;
    this.recipients = recipients;
    this.schools = schools;
    this.users = users;
    this.dispatcher = dispatcher;
    this.preferences = preferences;
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
    Instant now = Instant.now();

    // Stamp before the loop so a concurrent sweep tick cannot re-enter this item.
    item.markReminderSent(now);

    Language language = toDispatchLanguage(settings.getDefaultLanguage());
    String templateName = whatsAppProperties.getTemplate().getHomeworkReminderName();
    String dueDateText = DATE_FORMAT.format(item.getDueDate());

    List<HomeworkRecipient> recipientList = recipients.findAllByHomeworkId(homeworkId);
    int sent = 0;
    int deferred = 0;
    int suppressed = 0;
    int skipped = 0;

    for (HomeworkRecipient row : recipientList) {
      HomeworkDeliveryStatus status = row.getDeliveryStatus();
      if (status == HomeworkDeliveryStatus.SENT
          || status == HomeworkDeliveryStatus.FAILED
          || status == HomeworkDeliveryStatus.SUPPRESSED) {
        skipped++;
        continue;
      }
      if (status == HomeworkDeliveryStatus.DEFERRED) {
        deferred++;
        continue;
      }

      NotificationDecision decision =
          preferences.resolve(
              item.getSchoolId(), row.getParentUserId(), NotificationCategory.HOMEWORK, now);
      if (decision.suppressed()) {
        row.markSuppressed();
        suppressed++;
        continue;
      }
      if (decision.deferred()) {
        row.markDeferred(decision.deferUntil());
        deferred++;
        continue;
      }

      dispatchOne(
          row, item, item.getSubject(), dueDateText, templateName, language, decision.channels());
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
            deferred > 0 ? "deferred" : "ok")
        .increment();
    countCategory("notification.deferred", deferred);
    countCategory("notification.suppressed", suppressed);

    log.info(
        "homework_reminder_dispatched homeworkId={} sent={} deferred={} suppressed={} skipped={}",
        homeworkId,
        sent,
        deferred,
        suppressed,
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

    // Re-resolved rather than reused from the deferral: a parent who opted out during the hold has
    // expressed a newer intent than the one that put the row here.
    NotificationDecision decision =
        preferences.resolve(
            row.getSchoolId(), row.getParentUserId(), NotificationCategory.HOMEWORK, Instant.now());
    if (decision.suppressed()) {
      row.markSuppressed();
      return;
    }

    row.releaseFromDeferral();
    dispatchOne(
        row,
        item,
        item.getSubject(),
        DATE_FORMAT.format(item.getDueDate()),
        whatsAppProperties.getTemplate().getHomeworkReminderName(),
        language,
        decision.channels());

    log.info(
        "homework_reminder_deferred_released recipientId={} status={}",
        recipientId,
        row.getDeliveryStatus());
  }

  private void dispatchOne(
      HomeworkRecipient row,
      HomeworkItem item,
      String subject,
      String dueDateText,
      String templateName,
      Language language,
      List<NotificationChannel> channels) {
    User parent = users.findById(row.getParentUserId()).orElse(null);
    if (parent == null) {
      row.markFailed("parent_user_missing");
      return;
    }

    String smsBody = renderSmsBody(language, subject, dueDateText);
    DispatchRequest content =
        new DispatchRequest(
            parent.getPhone(),
            language,
            templateName,
            List.of(TemplateParam.of(subject), TemplateParam.of(dueDateText)),
            smsBody);
    // HashMap, not Map.of — this payload grows deep-link fields that are routinely null.
    Map<String, String> pushData = new HashMap<>();
    pushData.put("type", "homework");
    pushData.put("homeworkId", item.getId().toString());

    UserDispatchRequest request =
        new UserDispatchRequest(
            new NotificationTarget(row.getSchoolId(), row.getParentUserId(), parent.getPhone()),
            content,
            pushTitle(language),
            smsBody,
            pushData);
    try {
      DispatchResult result = dispatcher.dispatch(request, channels);
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
    Locale locale = localeOf(language);
    return messageSource.getMessage(
        "notification.whatsapp.template.homework_reminder",
        new Object[] {subject, dueDateText},
        "Homework: " + subject + ". Due " + dueDateText + ".",
        locale);
  }

  private String pushTitle(Language language) {
    return messageSource.getMessage(
        "notification.push.homework.title", null, "Homework reminder", localeOf(language));
  }

  private void countCategory(String metric, int amount) {
    if (amount > 0) {
      meterRegistry
          .counter(metric, "category", NotificationCategory.HOMEWORK.name())
          .increment(amount);
    }
  }

  private static Locale localeOf(Language language) {
    return language == Language.AR ? Locale.of("ar") : Locale.ENGLISH;
  }

  private static Language toDispatchLanguage(com.schoolbridge.api.tenant.Language source) {
    return switch (source) {
      case AR -> Language.AR;
      case EN -> Language.EN;
    };
  }
}
