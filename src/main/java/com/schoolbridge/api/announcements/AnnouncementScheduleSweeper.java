package com.schoolbridge.api.announcements;

import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.common.outbox.OutboxEventRecorder;
import com.schoolbridge.api.common.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Releases SCHEDULED announcements once {@code scheduledFor} arrives.
 *
 * <p>This closes a hole rather than adding a feature. {@code scheduledFor} and the SCHEDULED status
 * existed and the API accepted both, but nothing ever acted on them: the create path recorded the
 * dispatch event unconditionally, so a "send next Tuesday" announcement went out within seconds
 * while the UI still displayed SCHEDULED. Create now defers the event, and this sweeper records it
 * at the right time.
 *
 * <p>Recipients are materialised at create time, not here, so the recipient set is the one that
 * existed when the announcement was written â€” a student enrolled between scheduling and sending
 * does not silently receive it. That is the conservative choice; revisit it if schools ask for the
 * other behaviour.
 */
@Component
@ConditionalOnProperty(
    name = "schoolbridge.announcements.sweeper.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AnnouncementScheduleSweeper {

  private static final Logger log = LoggerFactory.getLogger(AnnouncementScheduleSweeper.class);
  private static final String AGGREGATE_TYPE = "Announcement";
  private static final String EVENT_CREATED = "announcement.created";

  private final AnnouncementRepository announcements;
  private final AnnouncementRecipientRepository recipients;
  private final OutboxEventRecorder outbox;
  private final TransactionTemplate tx;
  private final MeterRegistry meters;
  private final int batchSize;

  public AnnouncementScheduleSweeper(
      AnnouncementRepository announcements,
      AnnouncementRecipientRepository recipients,
      OutboxEventRecorder outbox,
      TransactionTemplate tx,
      MeterRegistry meters,
      @Value("${schoolbridge.announcements.sweeper.batch-size:100}") int batchSize) {
    this.announcements = announcements;
    this.recipients = recipients;
    this.outbox = outbox;
    this.tx = tx;
    this.meters = meters;
    this.batchSize = batchSize;
  }

  @Scheduled(
      fixedDelayString = "${schoolbridge.announcements.sweeper.release-rate:PT1M}",
      initialDelayString = "PT30S")
  public void releaseDueAnnouncements() {
    // Runs with no tenant bound so the query spans schools; the tenant is re-bound per row before
    // any further repository access, so the cross-tenant window closes immediately.
    TenantContext.clear();
    List<Announcement> due =
        tx.execute(
            s -> announcements.findDueScheduled(Instant.now(), PageRequest.of(0, batchSize)));
    if (due == null || due.isEmpty()) {
      return;
    }

    for (Announcement announcement : due) {
      try {
        release(announcement);
      } catch (RuntimeException ex) {
        // One bad announcement must not stall the rest of the batch; it stays SCHEDULED and is
        // retried on the next tick.
        log.error(
            "announcement_release_failed id={} school={}",
            announcement.getId(),
            announcement.getSchoolId(),
            ex);
      } finally {
        TenantContext.clear();
      }
    }
  }

  private void release(Announcement announcement) {
    TenantContext.set(announcement.getSchoolId());
    tx.executeWithoutResult(
        s -> {
          Announcement managed =
              announcements.findById(announcement.getId()).orElseThrow(IllegalStateException::new);
          // Re-check under the transaction: a recall (or another instance's sweep) may have moved
          // it since the unfiltered read above.
          if (managed.getStatus() != AnnouncementStatus.SCHEDULED) {
            return;
          }
          managed.markSent();

          long recipientCount = recipients.countByAnnouncementId(managed.getId());
          // HashMap, not Map.of â€” attachmentKey is nullable.
          Map<String, Object> payload = new HashMap<>();
          payload.put("announcementId", managed.getId());
          payload.put("schoolId", managed.getSchoolId());
          payload.put("language", managed.getLanguage());
          payload.put("body", managed.getBody());
          payload.put("attachmentKey", managed.getAttachmentKey());
          payload.put("recipientCount", recipientCount);
          outbox.record(
              managed.getSchoolId(), AGGREGATE_TYPE, managed.getId(), EVENT_CREATED, payload);

          meters.counter("announcement.scheduled.released").increment();
          log.info(
              "announcement_scheduled_released id={} school={} recipients={}",
              managed.getId(),
              managed.getSchoolId(),
              recipientCount);
        });
  }
}
