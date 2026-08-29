package com.schoolbridge.api.integrations.push;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Production FCM adapter backed by the Firebase Admin Java SDK. */
@Component
@ConditionalOnProperty(name = "schoolbridge.push.fcm.enabled", havingValue = "true")
public class FcmPushClient implements PushNotificationClient {

  private static final Logger log = LoggerFactory.getLogger(FcmPushClient.class);

  @Override
  public PushSendResult send(String fcmToken, String title, String body, Map<String, String> data) {
    Notification notification = Notification.builder().setTitle(title).setBody(body).build();
    Message.Builder builder = Message.builder().setToken(fcmToken).setNotification(notification);
    if (data != null) {
      data.forEach(builder::putData);
    }
    try {
      String messageId = FirebaseMessaging.getInstance().send(builder.build());
      return new PushSendResult(true, messageId);
    } catch (FirebaseMessagingException ex) {
      log.warn(
          "fcm_send_failed token_suffix={} error_code={} cause={}",
          safeSuffix(fcmToken),
          ex.getMessagingErrorCode(),
          ex.getMessage());
      return new PushSendResult(false, null);
    }
  }

  private static String safeSuffix(String token) {
    if (token == null || token.length() <= 4) {
      return "****";
    }
    return "****" + token.substring(token.length() - 4);
  }
}

