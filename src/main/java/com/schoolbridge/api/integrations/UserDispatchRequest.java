package com.schoolbridge.api.integrations;

import java.util.Map;

/**
 * A notification addressed to a known user, across any channel.
 *
 * <p>Wraps rather than extends {@link DispatchRequest} so the OTP path â€” which has no user, no
 * preferences, and must never acquire either â€” keeps compiling against the original record
 * unchanged. A parent cannot mute their own login code, so it does not belong on this type.
 *
 * @param target who to reach and how they can be reached
 * @param content the WhatsApp template and pre-rendered SMS body, unchanged from the M7 contract
 * @param pushTitle system-tray title; null disables the push channel for this message
 * @param pushBody system-tray body
 * @param pushData deep-link payload handed to the app; build it with {@code HashMap}, never {@code
 *     Map.of}, since entity ids in it are frequently null
 */
public record UserDispatchRequest(
    NotificationTarget target,
    DispatchRequest content,
    String pushTitle,
    String pushBody,
    Map<String, String> pushData) {

  public boolean hasPushContent() {
    return pushTitle != null && !pushTitle.isBlank();
  }
}

