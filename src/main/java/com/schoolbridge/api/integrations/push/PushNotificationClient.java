package com.schoolbridge.api.integrations.push;

import java.util.Map;

/**
 * Port for sending push notifications to a mobile device. Implementations: {@link FcmPushClient}
 * (production FCM), {@link LoggingPushClient} (dev stub).
 */
public interface PushNotificationClient {

  /**
   * Sends a push notification to the given FCM registration token.
   *
   * @param fcmToken device registration token obtained from the mobile SDK
   * @param title notification title (shown in system tray)
   * @param body notification body text
   * @param data arbitrary key-value data payload delivered to the app (may be empty, not null)
   */
  PushSendResult send(String fcmToken, String title, String body, Map<String, String> data);
}

