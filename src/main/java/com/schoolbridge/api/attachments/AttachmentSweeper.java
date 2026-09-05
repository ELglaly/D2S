package com.schoolbridge.api.attachments;

import com.schoolbridge.api.attachments.storage.ObjectStorage;
import com.schoolbridge.api.common.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes attachments nobody will ever use again.
 *
 * <p>Two distinct leaks, one pass:
 *
 * <ul>
 *   <li><b>Abandoned uploads.</b> The API is never told when a client's PUT lands â€” only the
 *       client calling {@code complete} distinguishes "still uploading" from "gave up". So a row
 *       stuck in {@code PENDING} or {@code UPLOADED} past the abandonment window is unreferenced by
 *       construction, and its object (which may or may not exist) is paid for forever.
 *   <li><b>Retention.</b> Photos of children should not accumulate indefinitely; the window is a
 *       policy knob rather than a hardcoded value because jurisdictions differ.
 * </ul>
 *
 * <p>Objects are deleted before rows. The other order risks losing the only pointer to an object
 * and leaking storage silently; this order can at worst delete an object whose row survives to the
 * next tick, which is visible and self-correcting.
 */
@Component
@ConditionalOnProperty(
    name = "schoolbridge.storage.sweeper.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AttachmentSweeper {

  private static final Logger log = LoggerFactory.getLogger(AttachmentSweeper.class);

  private final AttachmentRepository repository;
  private final ObjectStorage storage;
  private final TransactionTemplate tx;
  private final StorageProperties properties;

  public AttachmentSweeper(
      AttachmentRepository repository,
      ObjectStorage storage,
      TransactionTemplate tx,
      StorageProperties properties) {
    this.repository = repository;
    this.storage = storage;
    this.tx = tx;
    this.properties = properties;
  }

  @Scheduled(cron = "${schoolbridge.storage.sweeper.cron:0 15 3 * * *}")
  public void sweep() {
    // No tenant bound: this drains every school in one pass, like OutboxRelay. The queries backing
    // it are deliberately unscoped for that reason.
    TenantContext.clear();
    Instant now = Instant.now();
    int abandoned =
        purge(
            repository.findAbandoned(
                now.minus(properties.getSweeper().getAbandonedAfter()), batch()),
            "abandoned");
    int expired =
        purge(
            repository.findExpired(now.minus(properties.getSweeper().getRetention()), batch()),
            "retention");
    if (abandoned > 0 || expired > 0) {
      log.info("attachment_sweep abandoned={} expired={}", abandoned, expired);
    }
  }

  private int purge(List<Attachment> candidates, String reason) {
    if (candidates == null || candidates.isEmpty()) {
      return 0;
    }
    int deleted = 0;
    for (Attachment attachment : candidates) {
      try {
        storage.delete(attachment.getStorageKey());
        tx.executeWithoutResult(status -> repository.deleteById(attachment.getId()));
        deleted++;
      } catch (RuntimeException ex) {
        // One unreachable object must not stall the batch; the row stays and is retried next tick.
        log.warn(
            "attachment_sweep_failed id={} school={} reason={}",
            attachment.getId(),
            attachment.getSchoolId(),
            reason,
            ex);
      }
    }
    return deleted;
  }

  private PageRequest batch() {
    return PageRequest.of(0, properties.getSweeper().getBatchSize());
  }
}
