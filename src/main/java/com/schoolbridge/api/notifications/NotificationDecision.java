package com.schoolbridge.api.notifications;

import com.schoolbridge.api.integrations.NotificationChannel;
import java.time.Instant;
import java.util.List;

/**
 * What the resolver decided for one (user, category) pair at one instant. Exactly one of the three
 * outcomes applies, in this order:
 *
 * <ol>
 *   <li>{@code suppressed} — the user opted out; do not send, do not queue.
 *   <li>{@code deferUntil != null} — inside the quiet window; hold the recipient row until then.
 *   <li>otherwise — send now, trying {@code channels} in order.
 * </ol>
 *
 * <p>{@code channels} is populated in every case, including the suppressed one, so a caller that
 * ignores the outcome flags cannot accidentally send on an empty list.
 */
public record NotificationDecision(
    boolean suppressed, Instant deferUntil, List<NotificationChannel> channels) {

  public NotificationDecision {
    channels = List.copyOf(channels);
  }

  public static NotificationDecision sendNow(List<NotificationChannel> channels) {
    return new NotificationDecision(false, null, channels);
  }

  public static NotificationDecision defer(Instant until, List<NotificationChannel> channels) {
    return new NotificationDecision(false, until, channels);
  }

  public static NotificationDecision suppress(List<NotificationChannel> channels) {
    return new NotificationDecision(true, null, channels);
  }

  public boolean deferred() {
    return deferUntil != null;
  }
}
