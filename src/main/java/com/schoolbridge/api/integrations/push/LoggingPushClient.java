package com.schoolbridge.api.integrations.push;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op push client used when FCM is disabled. Logs the notification instead of sending it so local
 * development gets observable output without requiring Firebase credentials.
 */
@Component
@ConditionalOnProperty(
    name = "schoolbridge.push.fcm.enabled",
    havingValue = "false",
    matchIfMissing = true)
public class LoggingPushClient implements PushNotificationClient {

  private static final Logger log = LoggerFactory.getLogger(LoggingPushClient.class);

  @Override
  public PushSendResult send(String fcmToken, String title, String body, Map<String, String> data) {
    log.info("push_stub token_suffix={} title={} data={}", safeSuffix(fcmToken), title, data);
    return new PushSendResult(true, "stub-" + System.nanoTime());
  }

  private static String safeSuffix(String token) {
    if (token == null || token.length() <= 4) {
      return "****";
    }
    return "****" + token.substring(token.length() - 4);
  }
}
