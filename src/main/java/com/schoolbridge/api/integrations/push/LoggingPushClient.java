package com.schoolbridge.api.integrations.push;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op push client used when FCM is disabled. Logs the notification instead of sending it so local
 * development gets observable output without requiring Firebase credentials.
 *
 * <p>Reports the send as <b>not accepted</b>, because it was not: nothing left the process. That
 * matters now that push is first in {@code NotificationChannel.DEFAULT_ORDER} â€” the dispatcher
 * stops at the first channel that accepts, so a stub claiming success would end the walk and
 * swallow every notification for any user with a registered device, in exactly the deployment where
 * FCM has not been configured yet. Returning false lets the walk fall through to WhatsApp, and
 * makes the unconfigured state visible in {@code push.send.failure} instead of silent.
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
    log.info(
        "push_stub_not_sent token_suffix={} title={} data={}", safeSuffix(fcmToken), title, data);
    return new PushSendResult(false, null);
  }

  private static String safeSuffix(String token) {
    if (token == null || token.length() <= 4) {
      return "****";
    }
    return "****" + token.substring(token.length() - 4);
  }
}

