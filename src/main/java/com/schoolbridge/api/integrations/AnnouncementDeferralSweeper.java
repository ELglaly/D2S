package com.schoolbridge.api.integrations;

import com.schoolbridge.api.announcements.AnnouncementRecipient;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.common.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Releases announcement recipients whose quiet-hours hold has expired, mirroring {@code
 * AttendanceSweeper#releaseDeferredAlerts} and {@code HomeworkReminderSweeper}.
 *
 * <p>Lives in {@code integrations} rather than being folded into {@code
 * AnnouncementScheduleSweeper}, where the cron footprint would have been cheaper, because
 * announcement dispatch already lives here ({@link AnnouncementSendService}). Putting the release
 * scan in the {@code announcements} module would have made that domain module call into {@code
 * integrations} and closed a package cycle — the two modules would then depend on each other.
 *
 * <p>The scan runs with no tenant bound so it spans schools; each release is re-bound to the row's
 * own school before any further repository access, so the cross-tenant window closes immediately.
 */
@Component
@ConditionalOnProperty(
    name = "schoolbridge.announcements.deferral-sweeper.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AnnouncementDeferralSweeper {

  private static final Logger log = LoggerFactory.getLogger(AnnouncementDeferralSweeper.class);

  private final AnnouncementRecipientRepository recipients;
  private final AnnouncementSendService sendService;
  private final TransactionTemplate tx;

  public AnnouncementDeferralSweeper(
      AnnouncementRecipientRepository recipients,
      AnnouncementSendService sendService,
      TransactionTemplate tx) {
    this.recipients = recipients;
    this.sendService = sendService;
    this.tx = tx;
  }

  @Scheduled(
      fixedDelayString = "${schoolbridge.announcements.deferral-release-rate:PT1M}",
      initialDelayString = "PT45S")
  public void releaseDeferredRecipients() {
    TenantContext.clear();
    List<AnnouncementRecipient> due =
        tx.execute(s -> recipients.findDeferredReadyToDispatch(Instant.now()));
    if (due == null || due.isEmpty()) {
      return;
    }

    int released = 0;
    for (AnnouncementRecipient row : due) {
      UUID schoolId = row.getSchoolId();
      UUID recipientId = row.getId();
      try {
        TenantContext.runAs(
            schoolId,
            () -> {
              sendService.releaseDeferred(recipientId);
              return null;
            });
        released++;
      } catch (RuntimeException ex) {
        // One bad recipient must not stall the batch; the row stays DEFERRED and is retried.
        log.error(
            "announcement_deferred_release_failed recipientId={} schoolId={} cause={}",
            recipientId,
            schoolId,
            ex.getClass().getSimpleName() + ": " + ex.getMessage(),
            ex);
      } finally {
        TenantContext.clear();
      }
    }
    log.info("announcement_deferred_released count={}", released);
  }
}
