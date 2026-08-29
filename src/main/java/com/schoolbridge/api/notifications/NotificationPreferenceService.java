package com.schoolbridge.api.notifications;

import com.schoolbridge.api.notifications.dto.NotificationPreferencesRequest;
import com.schoolbridge.api.notifications.dto.NotificationPreferencesResponse;
import java.time.Instant;
import java.util.UUID;

/**
 * Resolves what should happen to one outbound notification for one user, and owns the self-service
 * read/write of the underlying rows.
 *
 * <p>The resolve step lives here rather than inside {@code NotificationDispatcher} because two of
 * the three outcomes â€” suppress and defer â€” are decisions about a <em>recipient row</em>, and the
 * dispatcher has no row. The fan-out services own that state machine already ({@code markDeferred}
 * / the sweepers), so they ask this service and act; the dispatcher is handed only the resulting
 * channel order.
 */
public interface NotificationPreferenceService {

  /**
   * Decides send / defer / suppress for one (user, category) at {@code now}.
   *
   * <p>Never suppresses or defers a non-mutable category (see {@link NotificationCategory}), and
   * never throws for a missing user or missing rows â€” an unreadable preference must degrade to
   * "send", because failing closed here would silently mute a parent.
   */
  NotificationDecision resolve(
      UUID schoolId, UUID userId, NotificationCategory category, Instant now);

  /** Current settings for a user, with defaults materialised for every category. */
  NotificationPreferencesResponse get(UUID schoolId, UUID userId);

  /** Replaces the whole preference set for a user. Rejects disabling a non-mutable category. */
  NotificationPreferencesResponse replace(
      UUID schoolId, UUID userId, NotificationPreferencesRequest request);
}

