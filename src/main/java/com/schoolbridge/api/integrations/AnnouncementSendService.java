package com.schoolbridge.api.integrations;

import com.schoolbridge.api.announcements.Announcement;
import com.schoolbridge.api.announcements.AnnouncementRecipient;
import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import com.schoolbridge.api.announcements.enums.DeliveryStatus;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.integrations.whatsapp.TemplateParam;
import com.schoolbridge.api.integrations.whatsapp.WhatsAppProperties;
import com.schoolbridge.api.notifications.NotificationCategory;
import com.schoolbridge.api.notifications.NotificationDecision;
import com.schoolbridge.api.notifications.NotificationPreferenceService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fans out a stored announcement to its materialized recipients via {@link NotificationDispatcher}.
 * The consumer wires this in (with the tenant context bound) once a {@code announcement.created}
 * outbox event is delivered.
 *
 * <p>Each call mutates the per-recipient {@code deliveryStatus} + {@code messageId}; it never
 * re-sends a recipient that already has a {@code messageId} so retries from RabbitMQ redeliveries
 * are idempotent at the recipient level.
 *
 * <p>Every recipient is resolved against their own notification preferences first, so one
 * announcement can go out to one parent immediately over push, be held until 07:00 for another, and
 * not be sent at all to a third who opted out. Announcements are the highest-volume parent-facing
 * message and were the last one with no deferral path, which is what made 22:00 notifications
 * possible at all.
 */
@Service
public class AnnouncementSendService {

  private static final Logger log = LoggerFactory.getLogger(AnnouncementSendService.class);
  private static final int BATCH_SIZE = 500;

  private final AnnouncementRecipientRepository recipients;
  private final AnnouncementRepository announcements;
  private final UserRepository users;
  private final NotificationDispatcher dispatcher;
  private final NotificationPreferenceService preferences;
  private final WhatsAppProperties whatsAppProperties;
  private final MessageSource messageSource;
  private final MeterRegistry meterRegistry;

  public AnnouncementSendService(
      AnnouncementRecipientRepository recipients,
      AnnouncementRepository announcements,
      UserRepository users,
      NotificationDispatcher dispatcher,
      NotificationPreferenceService preferences,
      WhatsAppProperties whatsAppProperties,
      MessageSource messageSource,
      MeterRegistry meterRegistry) {
    this.recipients = recipients;
    this.announcements = announcements;
    this.users = users;
    this.dispatcher = dispatcher;
    this.preferences = preferences;
    this.whatsAppProperties = whatsAppProperties;
    this.messageSource = messageSource;
    this.meterRegistry = meterRegistry;
  }

  @Transactional
  public void dispatchCreated(UUID announcementId, Language language, String body) {
    Pageable pageable = PageRequest.of(0, BATCH_SIZE);
    Page<AnnouncementRecipient> page;
    Instant now = Instant.now();
    int dispatched = 0;
    int deferred = 0;
    int suppressed = 0;
    do {
      page = recipients.findAllByAnnouncementId(announcementId, pageable);
      for (AnnouncementRecipient recipient : page.getContent()) {
        if (recipient.getMessageId() != null) {
          // Already dispatched in a previous attempt; redelivery â€” skip.
          continue;
        }
        DeliveryStatus status = recipient.getDeliveryStatus();
        if (status == DeliveryStatus.DEFERRED || status == DeliveryStatus.SUPPRESSED) {
          // A previous pass already decided this one; the sweeper owns DEFERRED from here.
          continue;
        }

        NotificationDecision decision =
            preferences.resolve(
                recipient.getSchoolId(),
                recipient.getParentUserId(),
                NotificationCategory.ANNOUNCEMENT,
                now);
        if (decision.suppressed()) {
          recipient.markSuppressed();
          suppressed++;
          continue;
        }
        if (decision.deferred()) {
          recipient.markDeferred(decision.deferUntil());
          deferred++;
          continue;
        }
        dispatchOne(recipient, language, body, decision.channels());
        dispatched++;
      }
      pageable = pageable.next();
    } while (page.hasNext());

    countCategory("notification.deferred", deferred);
    countCategory("notification.suppressed", suppressed);
    log.info(
        "announcement_created_dispatched announcementId={} dispatched={} deferred={} suppressed={}",
        announcementId,
        dispatched,
        deferred,
        suppressed);
  }

  /**
   * Releases one recipient whose quiet-hours hold has closed, and sends it. Invoked by {@link
   * AnnouncementDeferralSweeper} with the row's tenant already bound.
   *
   * <p>The announcement is re-read here rather than carried on the deferred row, because a hold can
   * last hours: one recalled overnight must not go out in the morning, and one whose body changed
   * should go out in its current form.
   */
  @Transactional
  public void releaseDeferred(UUID recipientId) {
    AnnouncementRecipient row = recipients.findById(recipientId).orElse(null);
    if (row == null || row.getDeliveryStatus() != DeliveryStatus.DEFERRED) {
      return;
    }
    Announcement announcement = announcements.findById(row.getAnnouncementId()).orElse(null);
    if (announcement == null || announcement.getStatus() == AnnouncementStatus.RECALLED) {
      row.markFailed();
      log.info("announcement_deferred_release_dropped recipientId={} reason=recalled", recipientId);
      return;
    }
    NotificationDecision decision =
        preferences.resolve(
            row.getSchoolId(),
            row.getParentUserId(),
            NotificationCategory.ANNOUNCEMENT,
            Instant.now());
    if (decision.suppressed()) {
      // The parent opted out during the hold. Honour the newer intent.
      row.markSuppressed();
      return;
    }
    row.releaseFromDeferral();
    dispatchOne(row, announcement.getLanguage(), announcement.getBody(), decision.channels());
  }

  @Transactional
  public void dispatchRecalled(UUID announcementId) {
    Pageable pageable = PageRequest.of(0, BATCH_SIZE);
    Page<AnnouncementRecipient> page;
    int marked = 0;
    do {
      page = recipients.findAllByAnnouncementId(announcementId, pageable);
      for (AnnouncementRecipient recipient : page.getContent()) {
        DeliveryStatus status = recipient.getDeliveryStatus();
        if (status == DeliveryStatus.DELIVERED || status == DeliveryStatus.READ) {
          // Already in the parent's inbox â€” recall does not unsend per OQ4 / option (b).
          continue;
        }
        if (status == DeliveryStatus.SUPPRESSED) {
          // Already terminal and never sent. Overwriting it with FAILED would turn an honoured
          // opt-out into a delivery miss in every report that counts them.
          continue;
        }
        recipient.markFailed();
        marked++;
      }
      pageable = pageable.next();
    } while (page.hasNext());
    log.info(
        "announcement_recalled_marked announcementId={} undeliveredFailed={}",
        announcementId,
        marked);
  }

  private void dispatchOne(
      AnnouncementRecipient recipient,
      Language language,
      String body,
      List<NotificationChannel> channels) {
    User parent = users.findById(recipient.getParentUserId()).orElse(null);
    if (parent == null) {
      log.warn(
          "announcement_recipient_missing announcementId={} recipientId={} parentId={}",
          recipient.getAnnouncementId(),
          recipient.getId(),
          recipient.getParentUserId());
      recipient.markFailed();
      return;
    }

    DispatchRequest content =
        new DispatchRequest(
            parent.getPhone(),
            language,
            whatsAppProperties.getTemplate().getAnnouncementName(),
            List.of(TemplateParam.of(body)),
            body);
    // HashMap, not Map.of â€” the announcement id is the only guaranteed-present entry and this map
    // grows deep-link fields that are routinely null.
    Map<String, String> pushData = new HashMap<>();
    pushData.put("type", "announcement");
    pushData.put("announcementId", recipient.getAnnouncementId().toString());

    UserDispatchRequest request =
        new UserDispatchRequest(
            new NotificationTarget(
                recipient.getSchoolId(), recipient.getParentUserId(), parent.getPhone()),
            content,
            pushTitle(language),
            body,
            pushData);

    DispatchResult result = dispatcher.dispatch(request, channels);
    if (result.accepted() && result.messageId() != null) {
      recipient.markSent(result.messageId());
    } else {
      recipient.markFailed();
    }
  }

  private String pushTitle(Language language) {
    Locale locale = language == Language.AR ? Locale.of("ar") : Locale.ENGLISH;
    return messageSource.getMessage(
        "notification.push.announcement.title", null, "New announcement", locale);
  }

  private void countCategory(String metric, int amount) {
    if (amount > 0) {
      meterRegistry
          .counter(metric, "category", NotificationCategory.ANNOUNCEMENT.name())
          .increment(amount);
    }
  }
}

