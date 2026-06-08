package com.schoolbridge.api.attendance;

import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.classes.entity.ParentStudentLink;
import com.schoolbridge.api.classes.entity.Student;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.classes.repository.StudentRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fans out an attendance alert (ABSENT / LATE / EXCUSED) to every linked parent of the affected
 * student. Mirrors {@code AnnouncementSendService} (M7), with two M8-specific behaviors:
 *
 * <ul>
 *   <li>When the school's {@link SchoolSettings#isAlertsRespectQuietHours()} is on and "now" is
 *       inside the school-local quiet window, the per-parent recipient row is materialized but
 *       moved to {@link AttendanceAlertStatus#DEFERRED} with {@code deferredUntil} set to the
 *       window's next-end instant. {@code AttendanceSweeper} (BP-3) releases these later.
 *   <li>The Meta-approved template name is chosen by triggering status, not by the announcement
 *       body — three templates for the three alerting statuses.
 * </ul>
 *
 * <p>{@code AttendanceRecord.alertSentAt} is stamped once every materialized recipient row has
 * reached a terminal state ({@link AttendanceAlertStatus#SENT} or {@link
 * AttendanceAlertStatus#FAILED}). DEFERRED rows hold the record's {@code alertSentAt} null until
 * the sweeper resolves them — which is the desired behavior for NFR-P2 dashboards.
 */
@Service
public class AttendanceAlertService {

  private static final Logger log = LoggerFactory.getLogger(AttendanceAlertService.class);
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

  private final AttendanceRecordRepository records;
  private final AttendanceAlertRecipientRepository recipients;
  private final ParentStudentLinkRepository parentLinks;
  private final UserRepository users;
  private final StudentRepository students;
  private final SchoolRepository schools;
  private final NotificationDispatcher dispatcher;
  private final WhatsAppProperties whatsAppProperties;
  private final MessageSource messageSource;

  public AttendanceAlertService(
      AttendanceRecordRepository records,
      AttendanceAlertRecipientRepository recipients,
      ParentStudentLinkRepository parentLinks,
      UserRepository users,
      StudentRepository students,
      SchoolRepository schools,
      NotificationDispatcher dispatcher,
      WhatsAppProperties whatsAppProperties,
      MessageSource messageSource) {
    this.records = records;
    this.recipients = recipients;
    this.parentLinks = parentLinks;
    this.users = users;
    this.students = students;
    this.schools = schools;
    this.dispatcher = dispatcher;
    this.whatsAppProperties = whatsAppProperties;
    this.messageSource = messageSource;
  }

  /**
   * Dispatches one attendance alert. Idempotent on redelivery: a recipient row that has already
   * reached {@link AttendanceAlertStatus#SENT} or {@link AttendanceAlertStatus#FAILED} is left
   * alone, and a row that is already {@link AttendanceAlertStatus#DEFERRED} is left for the sweeper
   * to handle.
   *
   * @param recordId target attendance record (must belong to the current tenant)
   * @param triggeringStatus the status that triggered the alert — selects the template name + SMS
   *     i18n key
   */
  @Transactional
  public void dispatchAlert(UUID recordId, AttendanceStatus triggeringStatus) {
    AttendanceRecord record = records.findById(recordId).orElse(null);
    if (record == null) {
      log.warn("attendance_alert_record_missing recordId={}", recordId);
      return;
    }
    if (record.getStatus() == AttendanceStatus.PRESENT) {
      // The triggering transition has been overwritten by a teacher correction between the outbox
      // write and the consumer drain. Drop the alert silently.
      log.info(
          "attendance_alert_skipped_no_longer_triggering recordId={} currentStatus=PRESENT",
          recordId);
      return;
    }

    School school = schools.findById(record.getSchoolId()).orElse(null);
    if (school == null) {
      log.warn(
          "attendance_alert_school_missing recordId={} schoolId={}",
          recordId,
          record.getSchoolId());
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

    Student student = students.findById(record.getStudentId()).orElse(null);
    if (student == null) {
      log.warn(
          "attendance_alert_student_missing recordId={} studentId={}",
          recordId,
          record.getStudentId());
      return;
    }

    Map<UUID, AttendanceAlertRecipient> existingByParent = new HashMap<>();
    for (AttendanceAlertRecipient existing : recipients.findAllByAttendanceRecordId(recordId)) {
      existingByParent.put(existing.getParentUserId(), existing);
    }

    List<ParentStudentLink> links = parentLinks.findAllByStudentId(record.getStudentId());
    boolean anyDeferred = false;
    int dispatched = 0;
    for (ParentStudentLink link : links) {
      AttendanceAlertRecipient row = existingByParent.get(link.getParentUserId());
      if (row != null) {
        AttendanceAlertStatus status = row.getDeliveryStatus();
        if (status == AttendanceAlertStatus.SENT || status == AttendanceAlertStatus.FAILED) {
          continue; // Already finalized; idempotent skip.
        }
        if (status == AttendanceAlertStatus.DEFERRED) {
          anyDeferred = true;
          continue; // Sweeper will release; do not re-dispatch here.
        }
        // PENDING: re-use the row.
      } else {
        row =
            recipients.save(
                new AttendanceAlertRecipient(
                    record.getSchoolId(), recordId, link.getParentUserId(), record.getStudentId()));
      }

      if (quietHoursApply) {
        row.markDeferred(deferredUntil);
        anyDeferred = true;
        continue;
      }

      dispatchOne(
          row,
          triggeringStatus,
          student,
          record.getDate(),
          toAnnouncementLanguage(settings.getDefaultLanguage()));
      dispatched++;
    }

    if (!anyDeferred && allRecipientsTerminal(recordId)) {
      record.markAlertSent(now);
    }
    log.info(
        "attendance_alert_processed recordId={} status={} dispatched={} deferred={}",
        recordId,
        triggeringStatus,
        dispatched,
        anyDeferred);
  }

  /**
   * Releases a single deferred recipient and dispatches it through the M7 pipeline. Invoked by
   * {@code AttendanceSweeper} (BP-3) once the school-local quiet window has closed.
   */
  @Transactional
  public void releaseDeferred(UUID recipientId) {
    AttendanceAlertRecipient row = recipients.findById(recipientId).orElse(null);
    if (row == null || row.getDeliveryStatus() != AttendanceAlertStatus.DEFERRED) {
      return;
    }
    AttendanceRecord record = records.findById(row.getAttendanceRecordId()).orElse(null);
    if (record == null) {
      log.warn(
          "attendance_alert_release_record_missing recipientId={} recordId={}",
          recipientId,
          row.getAttendanceRecordId());
      return;
    }
    if (record.getStatus() == AttendanceStatus.PRESENT) {
      row.markFailed("record_transitioned_to_present");
      return;
    }
    Student student = students.findById(record.getStudentId()).orElse(null);
    if (student == null) {
      row.markFailed("student_missing");
      return;
    }
    School school = schools.findById(record.getSchoolId()).orElse(null);
    Language language =
        school != null
            ? toAnnouncementLanguage(school.getSettings().getDefaultLanguage())
            : Language.EN;

    row.releaseFromDeferral();
    dispatchOne(row, record.getStatus(), student, record.getDate(), language);

    if (allRecipientsTerminal(record.getId())) {
      record.markAlertSent(Instant.now());
    }
  }

  private void dispatchOne(
      AttendanceAlertRecipient row,
      AttendanceStatus triggeringStatus,
      Student student,
      LocalDate date,
      Language language) {
    Optional<User> parentOpt = users.findById(row.getParentUserId());
    if (parentOpt.isEmpty()) {
      row.markFailed("parent_user_missing");
      return;
    }
    User parent = parentOpt.get();
    if (parent.getPhone() == null || parent.getPhone().isBlank()) {
      row.markFailed("parent_phone_missing");
      return;
    }

    String studentName = firstName(student.getFullName());
    String dateText = DATE_FORMAT.format(date);
    String templateName = templateNameFor(triggeringStatus);
    String smsBody = renderSmsBody(triggeringStatus, language, studentName, dateText);

    DispatchRequest request =
        new DispatchRequest(
            parent.getPhone(),
            language,
            templateName,
            List.of(TemplateParam.of(studentName), TemplateParam.of(dateText)),
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
          "attendance_alert_dispatch_failed recipientId={} cause={}",
          row.getId(),
          ex.getClass().getSimpleName() + ": " + ex.getMessage());
      row.markFailed(ex.getClass().getSimpleName());
    }
  }

  private boolean allRecipientsTerminal(UUID recordId) {
    for (AttendanceAlertRecipient r : recipients.findAllByAttendanceRecordId(recordId)) {
      AttendanceAlertStatus s = r.getDeliveryStatus();
      if (s != AttendanceAlertStatus.SENT && s != AttendanceAlertStatus.FAILED) {
        return false;
      }
    }
    return true;
  }

  private String templateNameFor(AttendanceStatus status) {
    WhatsAppProperties.Template t = whatsAppProperties.getTemplate();
    return switch (status) {
      case ABSENT -> t.getAttendanceAbsentName();
      case LATE -> t.getAttendanceLateName();
      case EXCUSED -> t.getAttendanceExcusedName();
      case PRESENT -> throw new IllegalStateException("PRESENT does not have an alert template");
    };
  }

  private String renderSmsBody(
      AttendanceStatus status, Language language, String studentName, String dateText) {
    String key = smsKeyFor(status);
    Locale locale = language == Language.AR ? Locale.of("ar") : Locale.ENGLISH;
    return messageSource.getMessage(key, new Object[] {studentName, dateText}, key, locale);
  }

  private static String smsKeyFor(AttendanceStatus status) {
    return switch (status) {
      case ABSENT -> "notification.whatsapp.template.attendance_absent";
      case LATE -> "notification.whatsapp.template.attendance_late";
      case EXCUSED -> "notification.whatsapp.template.attendance_excused";
      case PRESENT -> throw new IllegalStateException("PRESENT does not have an alert SMS body");
    };
  }

  private static String firstName(String fullName) {
    if (fullName == null) {
      return "";
    }
    int sp = fullName.indexOf(' ');
    return sp < 0 ? fullName : fullName.substring(0, sp);
  }

  /**
   * The M6 split left {@code tenant.Language} (school setting) and {@code announcements.Language}
   * (M7 dispatch contract) as parallel enums. {@code SchoolSettings} holds the former; {@link
   * DispatchRequest} demands the latter. Conversion stays here until a future cleanup unifies them.
   */
  private static Language toAnnouncementLanguage(com.schoolbridge.api.tenant.Language source) {
    return switch (source) {
      case AR -> Language.AR;
      case EN -> Language.EN;
    };
  }
}
