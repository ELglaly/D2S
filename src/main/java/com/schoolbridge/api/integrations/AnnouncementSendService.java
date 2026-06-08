package com.schoolbridge.api.integrations;

import com.schoolbridge.api.announcements.AnnouncementRecipient;
import com.schoolbridge.api.announcements.enums.DeliveryStatus;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.integrations.whatsapp.TemplateParam;
import com.schoolbridge.api.integrations.whatsapp.WhatsAppProperties;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Service
public class AnnouncementSendService {

  private static final Logger log = LoggerFactory.getLogger(AnnouncementSendService.class);
  private static final int BATCH_SIZE = 500;

  private final AnnouncementRecipientRepository recipients;
  private final UserRepository users;
  private final NotificationDispatcher dispatcher;
  private final WhatsAppProperties whatsAppProperties;

  public AnnouncementSendService(
      AnnouncementRecipientRepository recipients,
      UserRepository users,
      NotificationDispatcher dispatcher,
      WhatsAppProperties whatsAppProperties) {
    this.recipients = recipients;
    this.users = users;
    this.dispatcher = dispatcher;
    this.whatsAppProperties = whatsAppProperties;
  }

  @Transactional
  public void dispatchCreated(UUID announcementId, Language language, String body) {
    Pageable pageable = PageRequest.of(0, BATCH_SIZE);
    Page<AnnouncementRecipient> page;
    int totalDispatched = 0;
    do {
      page = recipients.findAllByAnnouncementId(announcementId, pageable);
      for (AnnouncementRecipient recipient : page.getContent()) {
        if (recipient.getMessageId() != null) {
          // Already dispatched in a previous attempt; redelivery — skip.
          continue;
        }
        dispatchOne(recipient, language, body);
        totalDispatched++;
      }
      pageable = pageable.next();
    } while (page.hasNext());
    log.info(
        "announcement_created_dispatched announcementId={} recipients={}",
        announcementId,
        totalDispatched);
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
          // Already in the parent's inbox — recall does not unsend per OQ4 / option (b).
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

  private void dispatchOne(AnnouncementRecipient recipient, Language language, String body) {
    User parent = users.findById(recipient.getParentUserId()).orElse(null);
    if (parent == null || parent.getPhone() == null || parent.getPhone().isBlank()) {
      log.warn(
          "announcement_recipient_no_phone announcementId={} recipientId={} parentId={}",
          recipient.getAnnouncementId(),
          recipient.getId(),
          recipient.getParentUserId());
      recipient.markFailed();
      return;
    }
    DispatchRequest request =
        new DispatchRequest(
            parent.getPhone(),
            language,
            whatsAppProperties.getTemplate().getAnnouncementName(),
            List.of(TemplateParam.of(body)),
            body);
    DispatchResult result = dispatcher.dispatch(request);
    if (result.accepted() && result.messageId() != null) {
      recipient.markSent(result.messageId());
    } else {
      recipient.markFailed();
    }
  }
}
