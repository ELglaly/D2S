package com.schoolbridge.api.integrations;

import java.util.UUID;

/**
 * Who a user-addressed notification is for.
 *
 * <p>Carries the user id, not just a phone number, because push is addressed by device token rather
 * than by phone â€” and because "was this parent notified" should have one answer keyed to a person,
 * not three answers keyed to whichever identifier a given channel happens to use.
 *
 * @param schoolId owning tenant; the device-token lookup is filtered by it
 * @param userId the recipient
 * @param phone E.164 number, or null when the user has none â€” the phone channels then simply fall
 *     through
 */
public record NotificationTarget(UUID schoolId, UUID userId, String phone) {

  public boolean hasPhone() {
    return phone != null && !phone.isBlank();
  }
}

